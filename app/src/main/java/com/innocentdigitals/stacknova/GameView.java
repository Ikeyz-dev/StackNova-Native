package com.innocentdigitals.stacknova;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Vibrator;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Random;

public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    static final float LW = 400f;      // logical width
    static final float BH = 30f;       // block height
    static final float BASE_W = 220f;  // starting block width

    enum State { MENU, PLAY, OVER }

    static class Block { float x, w, hue; Block(float x, float w, float hue) { this.x = x; this.w = w; this.hue = hue; } }
    static class Debris { float x, w, idx, vy, vx, hue, life = 1f; }
    static class Particle { float x, y, vx, vy, hue, life = 1f; }
    static class Ring { float x, w, idx, r = 10f, life = 1f; }

    private final Object lock = new Object();
    private final Random rnd = new Random();
    private final SharedPreferences prefs;
    private final Vibrator vibrator;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Thread thread;
    private volatile boolean running = false;

    private float scale = 0f, LH = 700f;

    private State state = State.MENU;
    private final ArrayList<Block> stack = new ArrayList<>();
    private final ArrayList<Debris> debris = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Ring> rings = new ArrayList<>();

    private boolean curActive = false;
    private float curX, curW, curHue, dir;
    private int score, combo, best;
    private float camY, shake, bgHue;
    private boolean newBest;
    private long overAt;

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        prefs = context.getSharedPreferences("stacknova", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        textPaint.setTextAlign(Paint.Align.CENTER);
        reset();
    }

    // ---------------- Lifecycle ----------------
    @Override public void surfaceCreated(SurfaceHolder holder) {
        running = true;
        thread = new Thread(this);
        thread.start();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        synchronized (lock) {
            scale = w / LW;
            LH = h / scale;
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        running = false;
        try { if (thread != null) thread.join(); } catch (InterruptedException ignored) {}
    }

    // ---------------- Input ----------------
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            synchronized (lock) { press(); }
        }
        return true;
    }

    // ---------------- Sound / haptics ----------------
    private void beep(final int freq, final int ms, final float vol) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    int rate = 22050;
                    int n = rate * ms / 1000;
                    short[] buf = new short[n];
                    for (int i = 0; i < n; i++) {
                        double env = 1.0 - (double) i / n;
                        buf[i] = (short) (Math.sin(2 * Math.PI * freq * i / rate) * env * vol * 32767);
                    }
                    AudioTrack t = new AudioTrack(AudioManager.STREAM_MUSIC, rate,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            n * 2, AudioTrack.MODE_STATIC);
                    t.write(buf, 0, n);
                    t.play();
                    Thread.sleep(ms + 60);
                    t.release();
                } catch (Exception ignored) {}
            }
        }).start();
    }

    @SuppressWarnings("deprecation")
    private void buzz(long ms) {
        try { if (vibrator != null) vibrator.vibrate(ms); } catch (Exception ignored) {}
    }

    // ---------------- Game logic ----------------
    private void reset() {
        stack.clear(); debris.clear(); particles.clear(); rings.clear();
        stack.add(new Block((LW - BASE_W) / 2f, BASE_W, 200f));
        score = 0; combo = 0; camY = 0; shake = 0; newBest = false; bgHue = 220f;
        spawn();
    }

    private float speed() { return 2.4f + Math.min(score * 0.07f, 5.2f); }

    private void spawn() {
        Block top = stack.get(stack.size() - 1);
        dir = (stack.size() % 2 == 0) ? 1f : -1f;
        curW = top.w;
        curX = dir == 1f ? -curW + 20f : LW - 20f;
        curHue = (200 + stack.size() * 9) % 360;
        curActive = true;
    }

    private void addParticles(float x, float idx, float hue, int n) {
        for (int i = 0; i < n; i++) {
            Particle p = new Particle();
            p.x = x; p.y = idx; p.hue = hue;
            p.vx = (rnd.nextFloat() - 0.5f) * 7f;
            p.vy = -rnd.nextFloat() * 6f - 1f;
            particles.add(p);
        }
    }

    private void press() {
        long now = System.currentTimeMillis();
        if (state == State.MENU) {
            reset(); state = State.PLAY; beep(440, 100, 0.15f);
        } else if (state == State.PLAY) {
            drop();
        } else if (state == State.OVER && now - overAt > 450) {
            reset(); state = State.PLAY;
        }
    }

    private void drop() {
        Block top = stack.get(stack.size() - 1);
        float left = Math.max(curX, top.x);
        float right = Math.min(curX + curW, top.x + top.w);
        float ow = right - left;
        int idx = stack.size();

        if (ow <= 0f) { gameOver(); return; }

        float diff = Math.abs(curX - top.x);
        Block placed;
        if (diff < 7f) {
            combo++;
            placed = new Block(top.x, top.w, curHue);
            if (combo >= 3 && placed.w < BASE_W) {
                float grow = Math.min(10f, BASE_W - placed.w);
                placed.x -= grow / 2f;
                placed.w += grow;
            }
            score += 1 + Math.min(combo, 5);
            Ring r = new Ring(); r.x = placed.x + placed.w / 2f; r.w = placed.w; r.idx = idx; rings.add(r);
            addParticles(placed.x + placed.w / 2f, idx, curHue, 14);
            beep(520 + combo * 70, 180, 0.18f);
            buzz(15);
        } else {
            combo = 0;
            placed = new Block(left, ow, curHue);
            Debris d = new Debris();
            d.idx = idx; d.hue = curHue;
            if (curX < top.x) { d.x = curX; d.w = top.x - curX; d.vx = -1.2f; }
            else { d.x = top.x + top.w; d.w = (curX + curW) - (top.x + top.w); d.vx = 1.2f; }
            debris.add(d);
            score += 1;
            beep(300, 100, 0.10f);
            buzz(8);
            shake = 4f;
        }
        stack.add(placed);
        spawn();
    }

    private void gameOver() {
        state = State.OVER;
        overAt = System.currentTimeMillis();
        Debris d = new Debris();
        d.x = curX; d.w = curW; d.idx = stack.size(); d.hue = curHue; d.vx = dir * 1.5f;
        debris.add(d);
        curActive = false;
        if (score > best) {
            best = score; newBest = true;
            prefs.edit().putInt("best", best).apply();
        }
        shake = 12f;
        beep(140, 500, 0.18f);
        buzz(120);
    }

    private void update(float dt) {
        if (state == State.PLAY && curActive) {
            curX += dir * speed() * dt;
            float minX = -curW + 20f, maxX = LW - 20f;
            if (curX > maxX) { curX = maxX; dir = -1f; }
            if (curX < minX) { curX = minX; dir = 1f; }
        }
        float targetCam = Math.max(0f, (stack.size() - 7) * BH);
        camY += (targetCam - camY) * 0.1f * dt;

        for (int i = debris.size() - 1; i >= 0; i--) {
            Debris d = debris.get(i);
            d.vy += 0.6f * dt; d.idx -= d.vy * dt / BH; d.x += d.vx * dt; d.life -= 0.012f * dt;
            if (d.life <= 0f) debris.remove(i);
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.vy += 0.3f * dt; p.x += p.vx * dt; p.y -= p.vy * dt / BH; p.life -= 0.03f * dt;
            if (p.life <= 0f) particles.remove(i);
        }
        for (int i = rings.size() - 1; i >= 0; i--) {
            Ring r = rings.get(i);
            r.r += 3f * dt; r.life -= 0.05f * dt;
            if (r.life <= 0f) rings.remove(i);
        }
        shake *= (float) Math.pow(0.9, dt);
        float targetHue = 220f + stack.size() * 6f;
        bgHue += (targetHue - bgHue) * 0.02f * dt;
    }

    // ---------------- Drawing ----------------
    private int hsv(float h, float s, float v) {
        float hh = ((h % 360f) + 360f) % 360f;
        return Color.HSVToColor(new float[]{hh, s, v});
    }

    private float sy(float idx) { return (LH - 140f) - (idx * BH - camY); }

    private void drawBlock(Canvas c, float x, float idx, float w, float hue, float alpha) {
        float y = sy(idx);
        int a = (int) (Math.max(0f, Math.min(1f, alpha)) * 255);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(hsv(hue, 0.65f, 0.95f)); paint.setAlpha(a);
        c.drawRect(x, y - BH, x + w, y, paint);
        paint.setColor(hsv(hue, 0.40f, 1.00f)); paint.setAlpha(a);
        c.drawRect(x, y - BH, x + w, y - BH + 5f, paint);
        paint.setColor(hsv(hue, 0.75f, 0.70f)); paint.setAlpha(a);
        c.drawRect(x, y - 5f, x + w, y, paint);
    }

    private void txt(Canvas c, String s, float x, float y, float size, int color, int alpha) {
        textPaint.setTextSize(size);
        textPaint.setColor(color);
        textPaint.setAlpha(alpha);
        c.drawText(s, x, y, textPaint);
    }

    private void draw(Canvas c) {
        if (scale <= 0f) return;
        long t = System.currentTimeMillis();
        float pulse = 0.6f + 0.4f * (float) Math.sin(t / 300.0);
        int pulseA = (int) (pulse * 255);

        c.save();
        c.scale(scale, scale);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 0, LH,
                hsv(bgHue, 0.60f, 0.28f), hsv(bgHue + 50f, 0.65f, 0.50f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, LW, LH, paint);
        paint.setShader(null);

        c.save();
        if (shake > 0.3f) c.translate((rnd.nextFloat() - 0.5f) * shake, (rnd.nextFloat() - 0.5f) * shake);

        for (int i = 0; i < stack.size(); i++) {
            float y = sy(i);
            if (y < -BH || y > LH + BH) continue;
            Block b = stack.get(i);
            drawBlock(c, b.x, i, b.w, b.hue, 1f);
        }
        if (curActive) drawBlock(c, curX, stack.size(), curW, curHue, 1f);
        for (Debris d : debris) drawBlock(c, d.x, d.idx, d.w, d.hue, d.life);

        for (Ring r : rings) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            paint.setColor(Color.WHITE);
            paint.setAlpha((int) (Math.max(0f, r.life) * 255));
            float y = sy(r.idx);
            c.drawRect(r.x - r.w / 2f - r.r / 2f, y - BH - r.r / 2f,
                    r.x + r.w / 2f + r.r / 2f, y + r.r / 2f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (Particle p : particles) {
            paint.setColor(hsv(p.hue, 0.5f, 1f));
            paint.setAlpha((int) (Math.max(0f, p.life) * 255));
            float y = sy(p.y) - BH / 2f;
            c.drawRect(p.x, y, p.x + 5f, y + 5f, paint);
        }
        c.restore();

        // HUD
        if (state == State.PLAY) {
            txt(c, String.valueOf(score), LW / 2f, 110f, 72f, Color.WHITE, 255);
            if (combo >= 2) txt(c, "PERFECT x" + combo, LW / 2f, 145f, 22f, 0xFFFFE66D, 255);
        } else if (state == State.MENU) {
            txt(c, "STACK NOVA", LW / 2f, LH * 0.28f, 52f, Color.WHITE, 255);
            txt(c, "Tap to drop. Line it up.", LW / 2f, LH * 0.28f + 38f, 20f, 0xFFCFD8FF, 255);
            txt(c, "Best: " + best, LW / 2f, LH * 0.28f + 76f, 24f, 0xFFFFE66D, 255);
            txt(c, "TAP TO START", LW / 2f, LH * 0.62f, 30f, Color.WHITE, pulseA);
        } else {
            paint.setColor(Color.BLACK); paint.setAlpha(140);
            c.drawRect(0, 0, LW, LH, paint);
            txt(c, "GAME OVER", LW / 2f, LH * 0.30f, 46f, 0xFFFF6B81, 255);
            txt(c, String.valueOf(score), LW / 2f, LH * 0.30f + 90f, 90f, Color.WHITE, 255);
            txt(c, newBest ? "NEW BEST!" : "Best: " + best, LW / 2f, LH * 0.30f + 135f, 26f, 0xFFFFE66D, 255);
            if (t - overAt > 450) txt(c, "TAP TO RETRY", LW / 2f, LH * 0.30f + 210f, 28f, Color.WHITE, pulseA);
        }

        c.restore();
    }

    // ---------------- Loop ----------------
    @Override public void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            float dt = (now - last) / 16_666_667f;
            last = now;
            if (dt > 3f) dt = 3f;

            Canvas c = null;
            try {
                c = getHolder().lockCanvas();
                if (c != null) {
                    synchronized (lock) {
                        update(dt);
                        draw(c);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) {
                    try { getHolder().unlockCanvasAndPost(c); } catch (Exception ignored) {}
                }
            }
            try { Thread.sleep(8); } catch (InterruptedException ignored) {}
        }
    }
}
