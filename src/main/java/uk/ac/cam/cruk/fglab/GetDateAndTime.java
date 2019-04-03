package uk.ac.cam.cruk.fglab;

import ij.ImagePlus;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.time.Duration;



public class GetDateAndTime {
	
	public static long getCurrentTimeInMs() {
		return System.currentTimeMillis();
	}
	
	public static long getCurrentTimeInSecond () {
		return (getCurrentTimeInMs()/1000);
	}
	
	public static String getCurrentTime() {
		Date now = new Date();
		SimpleDateFormat dateFormatter = new SimpleDateFormat("H:mm:ss','zzz");
		return "Time:," + dateFormatter.format(now);
	}
	
	public static String getCurrentDate() {
		Date now = new Date();
		SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy.MM.dd','E");
		return "Date:," + dateFormatter.format(now);
	}
	
	public static String getDuration(
			long milli
			) {
		Duration duration = Duration.ofMillis(milli);
		String str = duration.toString();
		return str.substring(2,str.length());
	}
}
