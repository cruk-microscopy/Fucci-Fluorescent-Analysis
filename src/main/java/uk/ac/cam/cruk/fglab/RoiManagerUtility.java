package uk.ac.cam.cruk.fglab;

import ij.gui.Roi;
import ij.IJ;
import ij.ImagePlus;
import ij.macro.Interpreter;
import ij.plugin.frame.RoiManager;

import java.awt.List;
import java.awt.Point;
import java.util.Hashtable;

public class RoiManagerUtility {

	public static Roi[] managerToRoiArray () {
		int rmState = checkManager();
		if (rmState != 2 && rmState != 4) // RoiManager need to be open with entry
			return null;
		RoiManager rm = RoiManager.getInstance();
		Roi[] rois = rm.getRoisAsArray();
		return rois;
	}
	
	public static void roiArrayToManager (
			Roi[] rois,
			Boolean modifyExist,
			Boolean append
			) {
		if (rois == null) return;
		RoiManager rm = RoiManager.getInstance();
		int rmState = checkManager();
		if (rmState == 2 || rmState == 4) {// RoiManager open with entry
			if (!modifyExist) return;
			if (!append) rm.reset();
		} else {
			rm = new RoiManager();
		}
		for (int i = 0; i < rois.length; i++) {
			rm.addRoi(rois[i]);
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
	
	
}
