package com.minispot.app;

import static org.junit.Assert.assertEquals;

import java.util.Calendar;
import java.util.TimeZone;

import org.junit.Test;

public class ReleaseDateTest {
	private static long utc(int year, int month, int day, int hour) {
		Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		c.clear();
		c.set(year, month - 1, day, hour, 0);
		return c.getTimeInMillis();
	}

	@Test
	public void daysSince() {
		long now = utc(2026, 10, 13, 15);
		assertEquals(0, RemoteActivity.daysSince("2026-10-13", now));
		assertEquals(1, RemoteActivity.daysSince("2026-10-12", now));
		assertEquals(229, RemoteActivity.daysSince("2026-02-26", now));
		assertEquals(0, RemoteActivity.daysSince("2026-10-20", now)); // future: today
		assertEquals(285, RemoteActivity.daysSince("2026", now)); // year precision: January 1st
		assertEquals(12, RemoteActivity.daysSince("2026-10", now)); // month precision: the 1st
		assertEquals(-1, RemoteActivity.daysSince("", now));
	}

	@Test
	public void shortDate() {
		assertEquals("26.02.26", RemoteActivity.shortDate("2026-02-26"));
		assertEquals("2026-02", RemoteActivity.shortDate("2026-02"));
		assertEquals("", RemoteActivity.shortDate(""));
	}
}
