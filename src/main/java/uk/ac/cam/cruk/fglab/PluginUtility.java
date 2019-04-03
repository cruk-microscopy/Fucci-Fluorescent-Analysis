package uk.ac.cam.cruk.fglab;

import java.io.BufferedInputStream;
import java.io.File;
//import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.HttpURLConnection;
//import java.net.MalformedURLException;
import java.net.URL;

import org.apache.commons.io.FilenameUtils;

import ij.IJ;
import ij.plugin.PluginInstaller;


public class PluginUtility {

	protected final static String url_TrackMateExtra = "http://maven.imagej.net/service/local/repositories/releases/content/org/scijava/TrackMate_extras/0.0.4/TrackMate_extras-0.0.4.jar";
	protected final static String url_FeatureJ = "https://imagescience.org/meijering/software/download/FeatureJ_.jar";
	protected final static String url_ImageScience = "https://imagescience.org/meijering/software/download/imagescience.jar";
	
	public static boolean installTrackMateExtras () {
		IJ.log("   Attempt installing TrackMate_extra.");
		try {
			if (!pluginInstaller(url_TrackMateExtra, pluginDir())) {
				return pluginDownloader(url_TrackMateExtra);
			} else {
				IJ.log("   \"TrackMate_extras\" installed successfully.");
				return true;
			}
		} catch (IOException e) {
			return false;
		}
	}
	
	public static boolean installFeatureJ () {
		IJ.log("   Attempt installing FeatureJ.");
		try {
			if (!pluginInstaller(url_FeatureJ, pluginDir())) {
				return pluginDownloader(url_FeatureJ);
			} else {
				IJ.log("   \"FeatureJ\" installed successfully.");
				return true;
			}
		} catch (IOException e) {
			return false;
		}
	}
	
	public static boolean installImageScience () {
		// image science plugin located in Fiji.app/jars
		IJ.log("   Attempt installing Image Science plugin.");
		try {
			if (!pluginInstaller(url_ImageScience, jarDir())) {
				return pluginDownloader(url_ImageScience);
			} else {
				IJ.log("   \"Imagescience\" installed successfully.");
				return true;
			}
		} catch (IOException e) {
			return false;
		}
	}
	
	public static boolean pluginDownloader(
			String pluginUrl
			) {
		boolean successInstalled = new PluginInstaller().install(pluginUrl);
		if (!successInstalled) {
			IJ.log("   plugin not installed!");
			IJ.log("   URL: " + pluginUrl);
		}
		return successInstalled;
	}
	
	public static boolean pluginInstaller(
			String pluginUrl,
			String installDir
			) throws IOException {
		
		if (pluginUrl == null) return false;
		URL url = new URL(pluginUrl);
		
		String pluginName = FilenameUtils.getName(url.getPath());
		
		if (installDir==null) {
			installDir = IJ.getDirectory("plugins");
		}
		String pluginPath =  installDir + File.separator + pluginName;
		if (pluginName == null) return false;

		// prepare download buffer stream
		BufferedInputStream inputStream;
		try {
			inputStream = new BufferedInputStream(url.openStream());  
		} catch (IOException e) {	// server denied access with error 403
			HttpURLConnection httpcon = (HttpURLConnection) url.openConnection();
			httpcon.addRequestProperty("User-Agent", "Mozilla/4.0");	// trick the server with dummy user agent request
			inputStream = new BufferedInputStream(httpcon.getInputStream());  
		}
		
		FileOutputStream fileOS = new FileOutputStream(pluginPath);
		byte[] data = new byte[1024];
		int byteContent;
		try {
		    while ((byteContent = inputStream.read(data, 0, 1024)) != -1) {
		        fileOS.write(data, 0, byteContent);
		    }
		    fileOS.close();
		} catch (IOException e) {
			IJ.log("   Cannot install plugin \"" + pluginName + "\"");
			IJ.log("   You will have to install it manually.");
		    return false;
		}
		
		return true;
	}
	
	public static boolean pluginCheck(
			String pluginKeywords,
			String pluginDir
			) {
		
		if (pluginDir == null) {
			pluginDir = pluginDir();
		}
		
		File f = new File(pluginDir);
		File[] matchingFiles = f.listFiles(new FilenameFilter() {
		    public boolean accept(File dir, String name) {
		        return name.contains(pluginKeywords) && name.endsWith("jar");
		    }
		});
		if (matchingFiles.length == 1) {
			return true;
		}
		else {
			IJ.log("   plugin \"" + pluginKeywords + "\" not found");
			return false;
		}
	}
	
	public static String pluginDir(
			) {
		return IJ.getDirectory("plugins");
	}
	
	public static String jarDir(
			) {
		return (IJ.getDirectory("imagej")+File.separator+"jars"+File.separator);
	}
	
	
}
