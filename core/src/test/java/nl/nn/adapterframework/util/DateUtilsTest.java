package nl.nn.adapterframework.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Calendar;
import java.util.Date;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * DateUtils Tester.
 * The assertions are in the form of testing the date in most cases, not the time
 * because of the time difference between the EU and US where Travis servers are located.
 * @author <Sina Sen>
 */
public class DateUtilsTest {

	public static String TZ = Calendar.getInstance().getTimeZone().getID();

	/**
	 * Tests have been written in CET timezone, change it here so Travis/Azure don't fail when running in other timezones
	 */
	@BeforeClass
	public static void setUp() {
		String timezone = "Europe/Amsterdam";
		System.out.println("settings timezone from ["+TZ+"] to [" + timezone + "]");
		System.setProperty("user.timezone", timezone);
	}

	@AfterClass
	public static void tearDown() {
		System.out.println("reverting timezone back to [" + TZ + "]");
		System.setProperty("user.timezone", TZ);
	}


	@Test
	public void testFormatLong() throws Exception {
		String date = DateUtils.format(1380924000000L);
		assertEquals("2013-10-05 00:00:00.000", date);
	}

	@Test
	public void testFormatDate() throws Exception {
		String date = DateUtils.format(new Date(1380924000000L));
		assertEquals("2013-10-05 00:00:00.000", date);
	}

	@Test
	public void testFormatForDateDateFormat() throws Exception {
		Date d = new Date(1500000000);
		String time = DateUtils.format(d, DateUtils.FORMAT_FULL_GENERIC);
		assertEquals("1970-01-18 09:40:00.000", time);

	}

	@Test
	public void testParseToDate() throws Exception {
		Date date = DateUtils.parseToDate("05-10-13", DateUtils.FORMAT_DATE);
		assertEquals(1380924000000L, date.getTime());
	}

	@Test
	public void testParseToDateFullYear() throws Exception {
		Date date = DateUtils.parseToDate("05-10-2014", DateUtils.FORMAT_DATE);
		assertEquals(1412460000000L, date.getTime());
	}

	@Test
	public void unableToParseDate() throws Exception {
		Date date = DateUtils.parseToDate("05/10/98", DateUtils.FORMAT_DATE);
		assertNull(date);
	}

	@Test
	public void unableToParseFullGenericWithoutTime() throws Exception {
		Date date = DateUtils.parseToDate("2000-01-01", DateUtils.FORMAT_FULL_GENERIC);
		assertNull(date);
	}

	@Test
	public void testParseXmlDate() throws Exception {
		Date date = DateUtils.parseXmlDateTime("2013-12-10");
		assertEquals(1386630000000L, date.getTime());
	}

	@Test
	public void testParseXmlDateTime() throws Exception {
		Date date = DateUtils.parseXmlDateTime("2013-12-10T12:41:43");
		assertEquals(1386675703000L, date.getTime());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testParseXmlInvalidDateTime() throws Exception {
		DateUtils.parseXmlDateTime("2013-12-10 12:41:43");
	}

	@Test
	public void testParseAnyDate1() throws Exception {
		Date date = DateUtils.parseAnyDate("12-2013-10");
		assertEquals(1386630000000L, date.getTime());
	}

	@Test
	public void testParseAnyDate2() throws Exception {
		Date date = DateUtils.parseAnyDate("2013-12-10 12:41:43");
		assertEquals(1386675703000L, date.getTime());
	}

	@Test
	public void testParseAnyDate3() throws Exception {
		Date date = DateUtils.parseAnyDate("05/10/98 05:47:13");
		assertEquals(907559233000L, date.getTime());
	}

	@Test
	public void testFormatOptimal() throws Exception {
		String date = DateUtils.formatOptimal(new Date(1386630000000L));
		assertEquals("2013-12-10", date);
	}

	@Test
	public void testFormatOptimalWithTime() throws Exception {
		String date = DateUtils.formatOptimal(new Date(1500000000));
		assertEquals("1970-01-18 09:40", date);
	}

	@Test
	public void testNextHigherValue() throws Exception {
		Date d = new Date(1500000000);
		Date date = DateUtils.nextHigherValue(d);
		assertEquals(1500060000L, date.getTime());
	}

	@Test
	public void testConvertDate() throws Exception {
		String date = DateUtils.convertDate(DateUtils.FORMAT_DATE, DateUtils.FORMAT_FULL_GENERIC, "18-03-13");
		assertEquals("2013-03-18 00:00:00.000", date);
	}

	@Test
	public void testChangeDateForDateYearsMonthsDays() throws Exception {
		String date = DateUtils.changeDate("2013-10-10", 2, 3, 5);
		assertEquals("2016-01-15", date);
	}

	@Test
	public void testChangeDateForDateYearsMonthsDaysDateFormat() throws Exception {
		String date = DateUtils.changeDate("10-10-13", 2, 3, 5, DateUtils.FORMAT_DATE);
		assertEquals("15-01-16", date);
	}

	@Test
	public void testIsSameDay() throws Exception {
		Date d1 = DateUtils.parseAnyDate("10-10-2013");
		Date d2 = DateUtils.parseAnyDate("2013-10-10");
		boolean b = DateUtils.isSameDay(d1, d2);
		assertEquals(true, b);
	}
}
