package com.lm.player.core.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongMatchingResolverTest {

    @Test
    fun allowsFourSecondDurationDifference() {
        assertTrue(
            SongMatchingResolver.isSongMatch(
                "晴天", "周杰伦", 269_000L,
                "晴天", "周杰伦", 273_000L
            )
        )
    }

    @Test
    fun rejectsClearlyDifferentDuration() {
        assertFalse(
            SongMatchingResolver.isSongMatch(
                "晴天", "周杰伦", 269_000L,
                "晴天", "周杰伦", 276_000L
            )
        )
    }

    @Test
    fun rejectsLiveVersionWithoutExplicitTitleMarker() {
        assertFalse(
            SongMatchingResolver.isSongMatch(
                "晴天", "周杰伦", 269_000L, "晴天", "周杰伦", 269_000L,
                "叶惠美", "2004 无与伦比演唱会 Live"
            )
        )
    }
}
