package uk.ac.cam.cruk.fglab;

import ij.gui.Roi;
import ij.io.RoiDecoder;
import ij.io.RoiEncoder;
import ij.IJ;
import ij.ImagePlus;
import ij.macro.Interpreter;
import ij.plugin.frame.RoiManager;


import java.awt.Point;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ROIUtility {
	
	// ROI Manager parameters
	protected static boolean rmOpen = false; 
	protected static Roi[] oriROIs = null;

	// function groups to handle ROI Manager
	protected static RoiManager prepareManager (boolean hide) {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null) {
			rmOpen = false;
			rm = new RoiManager();
		} else {
			rmOpen = true;
			oriROIs = rm.getRoisAsArray();
			rm.reset();
		}
		rm.setVisible(!hide);
		return rm;
	}
	protected static void restoreManager () {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null) {
			if (rmOpen) {
				rm = new RoiManager();
				for (Roi roi : oriROIs) {
					rm.addRoi(roi);
				}
				rm.setVisible(true);
			}
		} else {
			if (rmOpen) {
				rm.reset();
				for (Roi roi : oriROIs) {
					rm.addRoi(roi);
				}
				rm.setVisible(true);
			} else { rm.close(); }
		}
		return;
	}
	
	
	public static Roi[] managerToRoiArray () {
		int rmState = checkManager();
		if (rmState != 2 && rmState != 4) // RoiManager need to be open with entry
			return null;
		RoiManager rm = RoiManager.getInstance();
		Roi[] rois = rm.getRoisAsArray();
		return rois;
	}
	
	public static void roiArrayToManager (	//known issue, ROI slice number doesn't translate to RoiManager without active image
			Roi[] rois,
			Boolean modifyExist,
			Boolean append
			) {
		if (rois == null) return;
		RoiManager rm = RoiManager.getInstance2();
		int rmState = checkManager();
		if (rmState == 2 || rmState == 4) {// RoiManager open with entry
			if (!modifyExist) return;
			if (!append) rm.reset();
		} else {
			rm = new RoiManager();
		}
		for (int i = 0; i < rois.length; i++) {
			rm.add(rois[i], rois[i].getPosition());
		}
	}
	
	public static Boolean isOpen() {
		RoiManager rm = RoiManager.getInstance();
		if (rm == null && IJ.isMacro()) {
			rm = Interpreter.getBatchModeRoiManager();
		}
		if (rm == null) return false;
		else return true;
	}
	
	public static Boolean isEmpty() {
		RoiManager rm = RoiManager.getInstance();
		if (isOpen()) {
			if (rm.getCount() == 0) return true;
			else return false;
		} else {
			// RoiManager not open
			// open a new RoiManager and return true
			// rm = new RoiManager();
			return true;
		}
	}
	
	public static Boolean isInterpolatable() {
		RoiManager rm = RoiManager.getInstance();
		if (isOpen()) {
			if (rm.getCount() >= 2) return true;
			else return false;
		} else {
			return false;
		}
	}
	public static Boolean isHidden() {
		RoiManager rm = RoiManager.getInstance();
		if (isOpen()) {
			return !rm.isVisible();
		} else {
			// RoiManager not open, return false
			return false;
		}
	}
	
	public static Point getLocation() {
		RoiManager rm = RoiManager.getInstance();
		if (!isOpen()) return null;
		return new Point(rm.getLocation());
	}
	
	public static void setLocation(Point p) {
		RoiManager rm = RoiManager.getInstance();
		if (!isOpen()) return;
		rm.setLocation(p);
	}
	
	public static int checkManager() {
		// RoiManager state:
		// 0: not open;
		// 1: open without entry;
		// 2: open with entries;
		// 3: macro batch mode;
		// 4: hidden without entry;
		// 5: hidden with entries;
		int state = 0;
		RoiManager rm = RoiManager.getInstance();
		if (rm == null) {
			if (IJ.isMacro()) {
				rm = Interpreter.getBatchModeRoiManager();
				if (rm != null) state = 3;
			} else state = 0;
		} else {
			if (rm.getCount() == 0) {
				if (rm.isVisible()) state = 1;
				else state = 4;
			}
			else {
				if (rm.isVisible()) state = 2;
				else state = 5;
			}
		}
		return state;
	}
	
	public static void resetManager() {
		RoiManager rm = RoiManager.getInstance();
		int state = checkManager();
		if (state == 0) {
			rm = new RoiManager();
		} else if (state > 1) {
			rm.reset();
		}
		return;
	}
	
	public static void closeManager() {
		RoiManager rm = RoiManager.getInstance();
		if (isOpen()) {
			rm.reset();
			rm.close();
		}
		return;
	}
	
	public static void hideManager() {
		RoiManager rm = RoiManager.getInstance();
		if (rm == null && IJ.isMacro()) {
			rm = Interpreter.getBatchModeRoiManager();
		}
		if (rm == null) return;
		rm.setVisible(false);
	}
	
	public static void showManager() {
		RoiManager rm = RoiManager.getInstance();
		if (rm == null && IJ.isMacro()) {
			rm = Interpreter.getBatchModeRoiManager();
		}
		if (rm == null) return;
		rm.setVisible(true);
	}
	
	// sort ROI array by 1: track ID, 2: lineage ID, 3: frame
	public static Roi[] sortRoi (Roi[] rois) {
		if (rois==null || rois.length<=1) return rois;
		ArrayList<Integer> newIndex = sortRoi2(rois);
		if (newIndex.size() != rois.length) return rois;
		int nROI = rois.length;
		Roi[] sortedRois = new Roi[nROI];
		for (int i=0; i<nROI; i++) {
			sortedRois[i] = rois[ newIndex.get(i) ];
		}
		return sortedRois;
	}
	private static ArrayList<Integer> sortRoi2 (Roi[] rois) {
		int nROI = rois.length;
		ArrayList<Integer> index = new ArrayList<Integer>();
		String[] names = new String[nROI];	// ROI name array
		int[] posT = new int[nROI];			// frame position array
		for (int i=0; i<nROI; i++) {
			index.add(i);
			names[i] = rois[i].getName();
			posT[i] = rois[i].getTPosition();
		}
		int[] trackID = new int[nROI];		// track ID array
		String[] lineageID = new String[nROI];	// sub-lineage array
		for (int i=0; i<nROI; i++) {
			trackID[i] = getTrack(names[i]);
			lineageID[i] = getSubLineage(names[i]);
		}
		Collections.sort(index, new Comparator<Integer>() {
	        public int compare(Integer idx1, Integer idx2) {
	        	Integer v1 = posT[idx1];	Integer v2 = posT[idx2];
	        	if (trackID[idx1] != trackID[idx2]) {	// 1: sort by Track ID
	        		v1 = trackID[idx1]; v2 = trackID[idx2];
	        		return v1.compareTo(v2);
	        	} else if (!lineageID[idx1].contentEquals(lineageID[idx2])) {// 2: sort by sub-lineage
	        		String l1 = lineageID[idx1]; 	String l2 = lineageID[idx2];
	        		int len1 = l1.length();			int len2 = l2.length();
	        		int len = Math.min(len1, len2);
	        		if (len != 0) {	// check if there's root lineage
	        			//println("ROI 1: " + names[idx1]); //println("ROI 2: " + names[idx2]);
		        		for (int i=0; i<len; i++) {
		        			int c1 = Integer.valueOf( l1.substring(i, i+1) );
		        			int c2 = Integer.valueOf( l2.substring(i, i+1) );
		        			if (c1 != c2) {	// for each digit position, 0 always present before 1
		        				v1 = c1; v2 = c2;
		        				return v1.compareTo(v2);
		        			}
		        		}
	        		}
	        		v1 = len1; v2 = len2;
	        		return v1.compareTo(v2);
	        	} else {	// 3: sort by frame
	        		return v1.compareTo(v2);
	        	}
	    }});
	    return index;
	}
	// get lineage ID: ID:[311-100001]
	private static String getLineage (String name) {
		int idx1 = name.indexOf("ID:");
		if (idx1 == -1) return null;
		int idx2 = name.indexOf(",", idx1);
		if (idx2 == -1) idx2 = name.length();
		return name.substring(idx1+3, idx2);
	}
	// get track ID: 311
	private static int getTrack (String name) {
		String lineage = getLineage(name);
		if (null == lineage || lineage.length()==0) return -1;
		int idx = lineage.indexOf("-");
		if (idx == -1) return -1;
		return Integer.valueOf(lineage.substring(0, idx));
	}
	// get sub-lineage ID: 100001
	private static String getSubLineage (String name) {
		String lineage = getLineage(name);
		if (null == lineage || lineage.length()==0) return null;
		int idx = lineage.indexOf("-");
		if (idx == -1) return null;
		return lineage.substring(idx+1, lineage.length());
	}
	
	// show ROI in ROI Manager
	public static void showRoisWithManager (Roi[] roiArray, ImagePlus imp) {
		if (roiArray==null || roiArray.length==0 || imp==null) return;
		RoiManager rm = prepareManager(true);
        for (int i=0; i<roiArray.length; i++) {
        	rm.add(imp, roiArray[i], -1);
        }
        rm.setVisible(true);
	}
	// save ROI using ROI Manager
	public static boolean saveRoisWithManager (ArrayList<Roi> roiList, String path, ImagePlus imp) {
		if (roiList==null || roiList.size()==0) return false;
		Roi[] roiArray = new Roi[roiList.size()];
		for (int i=0; i<roiList.size(); i++)
			roiArray[i] = roiList.get(i);
		return saveRoisWithManager (roiArray, path, imp);
	}
	public static boolean saveRoisWithManager (Roi[] roiArray, String path, ImagePlus imp) {
		if (roiArray==null || roiArray.length==0 || imp==null) return false;
        if (path==null || !path.toLowerCase().endsWith(".zip")) return false;
        RoiManager rm = prepareManager(true);
        for (int i=0; i<roiArray.length; i++) {
        	rm.add(imp, roiArray[i], -1);
        }
        rm.runCommand("Save", path);
        restoreManager();
        return true;
	}
	
	// an almost duplicate of ImageJ ROI Manager Save function, to save ROIs to disk
	public static boolean saveRois (ArrayList<Roi> roiList, String path) {
		if (roiList==null || roiList.size()==0) return false;
		Roi[] roiArray = new Roi[roiList.size()];
		for (int i=0; i<roiList.size(); i++)
			roiArray[i] = roiList.get(i);
		return saveRois (roiArray, path);
	}
	public static boolean saveRois (Roi[] roiArray, String path) {
		if (roiArray==null || roiArray.length==0) return false;
        if (path==null || !path.toLowerCase().endsWith(".zip")) return false;
        
        DataOutputStream out = null;
        IJ.showStatus("Saving "+roiArray.length+" ROIs "+" to "+path);
        long t0 = System.currentTimeMillis();
        
        String[] names = new String[roiArray.length];
        for (int i=0; i<roiArray.length; i++)
            names[i] = roiArray[i].getName();
        
        
        try {
            ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(path)));
            out = new DataOutputStream(new BufferedOutputStream(zos));
            RoiEncoder re = new RoiEncoder(out);
            for (int i=0; i<roiArray.length; i++) {
                IJ.showProgress(i, roiArray.length);
                String label = getUniqueName(names, i);
                Roi roi = roiArray[i];
                if (roi==null) continue;
                if (!label.endsWith(".roi")) label += ".roi";
                zos.putNextEntry(new ZipEntry(label));
                re.write(roi);
                out.flush();
            }
            out.close();
        } catch (IOException e) {
            System.out.println(e);
            return false;
        } finally {
            if (out!=null)
                try {out.close();} catch (IOException e) {}
        }
        double time = (System.currentTimeMillis()-t0)/1000.0;
        IJ.showProgress(1.0);
        IJ.showStatus(IJ.d2s(time,3)+" seconds, "+roiArray.length+" ROIs, "+path);

        return true;
    }
	
	// an almost duplicate of ImageJ ROI Manager Open function, to load ROI array from disk
	public static Roi[] loadRoiArray (String path) {
		ArrayList<Roi> roiList = loadRoiList (path);
		if (roiList==null || roiList.size()==0) return null;
		Roi[] roiArray = new Roi[roiList.size()];
		for (int i=0; i<roiList.size(); i++)
			roiArray[i] = roiList.get(i);
		return roiArray;
	}
	public static ArrayList<Roi> loadRoiList (String path) {
		if ( path==null || !path.toLowerCase().endsWith(".zip") || !(new File(path)).exists() ) return null;
	    
		ZipInputStream in = null;	ByteArrayOutputStream out = null;
        
        ArrayList<Roi> roiList = new ArrayList<Roi>();
        
        try {
            in = new ZipInputStream(new FileInputStream(path));
            byte[] buf = new byte[1024];
            int len;
            ZipEntry entry = in.getNextEntry();
            while (entry!=null) {
                String name = entry.getName();
                if (name.endsWith(".roi")) {
                    out = new ByteArrayOutputStream();
                    while ((len = in.read(buf)) > 0)
                        out.write(buf, 0, len);
                    out.close();
                    byte[] bytes = out.toByteArray();
                    RoiDecoder rd = new RoiDecoder(bytes, name);
                    Roi roi = rd.getRoi();
                    if (roi!=null) {
                        name = name.substring(0, name.length()-4);
                        roi.setName(name);
                        if (!roi.hasHyperStackPosition())
                        	roi.setPosition(0, 0, roi.getPosition());
                        roiList.add(roi);
                    }
                }
                entry = in.getNextEntry();
            }
            in.close();
        } catch (IOException e) {
            System.out.println("Load ROI fail: " + e);
        } finally {
            if (in!=null)
                try {in.close();} catch (IOException e) {}
            if (out!=null)
                try {out.close();} catch (IOException e) {}
        }
                
        return roiList;
	}
	
	
	private static String getUniqueName(String[] names, int index) {
        String name = names[index];
        int n = 1;
        int index2 = getIndex(names, index, name);
        while (index2!=-1) {
            index2 = getIndex(names, index, name);
            if (index2!=-1) {
                int lastDash = name.lastIndexOf("-");
                if (lastDash!=-1 && name.length()-lastDash<5)
                    name = name.substring(0, lastDash);
                name = name+"-"+n;
                n++;
            }
            index2 = getIndex(names, index, name);
        }
        names[index] = name;
        return name;
    }
	private static int getIndex(String[] names, int index, String name) {
        int index2 = -1;
        for (int i=0; i<names.length; i++) {
            if (i!=index && names[i].equals(name))
            return i;
        }
        return index2;
    }
	
}
