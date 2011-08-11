package adaptorlib;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.ExpectedException;


import java.util.Calendar;
import java.util.Date;

/**
 * Tests for {@link ScheduleOncePerDay}.
 */
public class ScheduleOncePerDayTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testIteration() {
    // Simple case of no DST changeover
    final int millisecondsInADay = 1000 * 60 * 60 * 24;
    Date date = new Date(0);
    // localtime's 7:00 AM on January 1st in utc (assuming northern hemisphere -
    // thus no DST)
    final long startTime = 1000 * 60 * 60 * 7
        - Calendar.getInstance().get(Calendar.ZONE_OFFSET);
    ScheduleOncePerDay it = new ScheduleOncePerDay(7, 0, 0, date);
    Date cur = it.next();
    assertEquals(startTime + millisecondsInADay * 0, cur.getTime());
    cur = it.next();
    assertEquals(startTime + millisecondsInADay * 1, cur.getTime());
    cur = it.next();
    assertEquals(startTime + millisecondsInADay * 2, cur.getTime());
    cur = it.next();
    assertEquals(startTime + millisecondsInADay * 3, cur.getTime());
    assertTrue(it.hasNext());
  }

  @Test
  public void testAlreadyHappenedToday() {
    // Just after midnight
    Date now = new Date(1);
    ScheduleOncePerDay it = new ScheduleOncePerDay(0, 0, 0, now);
    Date nextWakeup = it.next();
    assertTrue(now.before(nextWakeup));
    Date tomorrow = new Date(now.getTime() + 1000 * 60 * 60 * 24);
    assertTrue(tomorrow.after(nextWakeup));
  }

  @Test
  public void testRemove() {
    ScheduleOncePerDay it = new ScheduleOncePerDay(23, 59, 59);
    thrown.expect(UnsupportedOperationException.class);
    it.remove();
  }
}
