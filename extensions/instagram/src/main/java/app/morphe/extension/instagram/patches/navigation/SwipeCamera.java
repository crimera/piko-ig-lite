/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */
package app.morphe.extension.instagram.patches.navigation;

import app.morphe.extension.instagram.settings.Settings;

/**
 * Injection points of the swipe-to-camera hook. Positions are in units of the screen width: the feed
 * rests at the centre, the camera panel is on the negative side and Direct on the positive side.
 */
@SuppressWarnings("unused")
public final class SwipeCamera {
    /** The reason the swipe container gives every move that comes from a finger or a nested scroll. */
    private static final String SWIPE_REASON = "swipe";

    /** How close to the centre a position must be to count as resting on the feed. */
    private static final float FEED_EPSILON = 0.001f;

    private SwipeCamera() {
    }

    /**
     * The lower bound the feed holds a move to. A finger or nested-scroll move is held at the centre; every
     * other reason (the camera button, deep links, story recovery) is a deliberate navigation and gets an
     * unbounded floor, so {@link #clampAtFeed} leaves it alone.
     */
    public static float feedBound(String reason, float centre) {
        return isSwipeReason(reason) ? centre : Float.NEGATIVE_INFINITY;
    }

    /**
     * Returns the target a move should travel to. A move that starts on the feed (or on Direct) stops at
     * the bound instead of crossing into the camera panel. A move that starts on the camera passes through.
     */
    public static float clampAtFeed(float current, float target, float bound) {
        boolean restingOnFeed = current >= bound - FEED_EPSILON;
        return restingOnFeed && target < bound ? bound : target;
    }

    private static boolean isSwipeReason(String reason) {
        return SWIPE_REASON.equals(reason) && Settings.swipeCameraGesture();
    }

    /**
     * True when a release that starts from rest on the feed would snap to the camera panel. The caller
     * zeroes the release velocity then, so the spring holds the feed instead of flinging into the camera.
     */
    public static boolean holdFeedRelease(float current, float centre, float velocity) {
        boolean restingOnFeed = Math.abs(current - centre) <= FEED_EPSILON;
        return restingOnFeed && velocity < 0f && Settings.swipeCameraGesture();
    }
}
