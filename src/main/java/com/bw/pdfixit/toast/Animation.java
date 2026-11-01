package com.bw.pdfixit.toast;

import javax.swing.Timer;
import java.awt.Point;
import java.awt.geom.Point2D;

public class Animation {

    public int targetX;
    public int targetY;
    public float dX;
    public float dY;
    public int durationMS;
    public Toast flyingToast;
    public Point2D.Float pos;

    public Timer timer =
            new Timer(20, e -> {
                float dx = (targetX - pos.x);
                float dy = (targetY - pos.y);
                if (Math.abs(dx) <= Math.abs(dX) && Math.abs(dy) <= Math.abs(dY)) {
                    finish();
                } else {
                    pos.x += dX;
                    pos.y += dY;
                    flyingToast.setLocation((int) pos.x, (int) pos.y);
                }
            });

    public void finish() {
        flyingToast.setLocation(targetX, targetY);
        Animation.this.timer.stop();
        if (this == flyingToast.animation)
            flyingToast.animation = null;
        flyingToast = null;
    }

    public Animation(Toast c, int durationMS, int x, int y) {
        this.durationMS = durationMS;
        this.flyingToast = c;
        this.targetX = x;
        this.targetY = y;
        Point pt = flyingToast.getLocation();
        this.pos = new Point2D.Float(pt.x, pt.y);
        this.dX = (x - pos.x) / (durationMS / 20f);
        this.dY = (y - pos.y) / (durationMS / 20f);

        timer.start();
    }

    public void abort() {
        Animation.this.timer.stop();
        if (this == flyingToast.animation)
            flyingToast.animation = null;
        flyingToast = null;
    }
}
