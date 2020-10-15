package uk.ac.cam.cruk.fglab;

import ij.*;
import ij.gui.*;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;
import ij.plugin.CanvasResizer;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.PlugInFrame;

import java.awt.*;
import java.util.List;
import java.awt.event.*;
import java.io.IOException;

import javax.swing.*;
import javax.swing.border.EmptyBorder;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.traverse.GraphIterator;
import org.scijava.prefs.DefaultPrefService;

import fiji.plugin.trackmate.Logger;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.SelectionModel;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.SpotCollection;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.detection.ManualDetectorFactory;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import fiji.plugin.trackmate.gui.TrackMateGUIController;
import fiji.plugin.trackmate.providers.EdgeAnalyzerProvider;
import fiji.plugin.trackmate.providers.SpotAnalyzerProvider;
import fiji.plugin.trackmate.providers.TrackAnalyzerProvider;
import fiji.plugin.trackmate.tracking.LAPUtils;
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory;
import fiji.plugin.trackmate.visualization.hyperstack.HyperStackDisplayer;
import fiji.util.gui.GenericDialogPlus;

import java.util.*;

public class TrackManager implements PlugIn{
	
	private ImagePlus imp;
	private RoiManager rm;
	private Roi[] allRois;
	private Roi[] selectedRois;
	private ArrayList<ArrayList<Roi>> trackedCells;
	
	// auto correction parameter
	private Roi[] cellROIArray;
	private HashMap<Integer, ArrayList<Integer>> frameROIs;
	private String tablePath;
	private ResultsTable table;
	private double minCost = 150;
	private double maxCount = 1000;
	private double rangeXY = 20;
	private double rangeT = 1;
	private double minSimScore = 8.0;
	
	
	//protected ResultsTable result;
	HashMap<String, Double> linkMap;
	protected final String pointer = " \u27F6 ";
	//protected final String pointer = " ==> ";
	//String[] linkArray;
	//double[] linkCostArray;
	
	protected boolean cropSilent = true;
	protected int cropSize = 40;
	// parameters for GUI
	protected PlugInFrame pf;
	protected final int lineWidth = 40;
	protected final Color panelColor = new Color(204, 229, 255);
	protected final Font textFont = new Font("Helvetica", Font.PLAIN, 12);
	protected final Color fontColor = Color.BLACK;
	protected final Font errorFont = new Font("Helvetica", Font.BOLD, 12);
	protected final Color errorFontColor = Color.RED;
	protected final Color textAreaColor = new Color(204, 229 , 255);
	protected final Font panelTitleFont = new Font("Helvetica", Font.BOLD, 13);
	protected final Color panelTitleColor = Color.BLUE;
	protected final EmptyBorder border = new EmptyBorder(new Insets(5, 5, 5, 5));
	protected final Dimension textAreaMax = new Dimension(260, 150);
	protected final Dimension tablePreferred = new Dimension(260, 100);
	protected final Dimension tableMax = new Dimension(260, 150);
	protected final Dimension panelTitleMax = new Dimension(500, 30);
	protected final Dimension panelParentSize =new Dimension(550, 900);
	protected final Dimension panelContentSize =new Dimension(300, 200);
	protected final Dimension panelMax = new Dimension(600, 600);
	protected final Dimension panelMin = new Dimension(300, 200);
	protected final Dimension buttonSize = new Dimension(90, 10);
	
	
	protected JTextArea lineageInfo;
	protected JComboBox lineageList;
	protected JComboBox trackList;
	
	protected JComboBox sortTracks;
	protected String[] sortOptions = {"ID", "length", "start", "#lineage", "#ROI", "#mitosis"};
	
	protected JComboBox sourceList;
	protected JComboBox linkList;
	protected JComboBox targetList;
	protected String[] sourceArray = {""};
	protected String[] linkArray = {""};
	protected String[] targetArray = {""};
	protected String selectedSpotRoiName;
	protected spotRoi sourceSpot = null;
	protected spotRoi targetSpot = null;
	protected int savedRmIndex = -1;
	
	protected ArrayList<spotRoi> spotRois;
	protected ArrayList<Integer> trackIDs;
	
	protected int selectedTrack;
	protected String selectedLineage;
	
	protected Integer[] trackIDArray;
	protected Integer[] trackIDArray_ID;
	protected Integer[] trackIDArray_length;
	protected Integer[] trackIDArray_start;
	protected Integer[] trackIDArray_lineage;
	protected Integer[] trackIDArray_ROI;
	protected Integer[] trackIDArray_mitosis;
	
	protected ArrayList<spotRoi> trackRois;
	protected ArrayList<String> lineage;
	protected String[] lineageArray;
	
	
	
	/* class to store information of trackmate spot Rois
	 * 
	 */
	public class spotRoi { 
		private int spotID; //the same as spotID
		private int trackID;
		private String lineageID;
		private String roiName;
		private String roiLabel;	// mitosis?
		private Roi roi;
		private boolean isPoint;
		private double x;
		private double y;
		private double t;
		private double d; // diameter
		private String linkString;
		private double linkCost;
		private int managerIdx;
		// default constructor
		public spotRoi(int roiManagerIndex) {
			RoiManager rm = RoiManager.getInstance();
			if (rm==null || rm.getCount()<roiManagerIndex+1) return;
			roiName = rm.getName(roiManagerIndex);
			String[] nameParts = roiName.split(",");
			if (nameParts.length<2) return;
			String roiPart = nameParts[0].toLowerCase();
			String iDPart = nameParts[1].toLowerCase();
			String labelPart = "";
			if (nameParts.length>2) labelPart = nameParts[2];
			spotID = Integer.valueOf(roiPart.substring(roiPart.indexOf("roi:")+4, roiPart.length()));
			int idIdx1 = iDPart.indexOf("id:")+3;
			int idIdx2 = iDPart.indexOf("-");
			trackID = Integer.valueOf(iDPart.substring(idIdx1, idIdx2));
			lineageID = iDPart.substring(idIdx2+1, iDPart.length());
			roiLabel = labelPart;
			roi = rm.getRoi(roiManagerIndex);
			isPoint = (roi.getType() == Roi.POINT);
			double[] center = roi.getContourCentroid();
			x = isPoint ? roi.getXBase() : center[0]; // pixel, not micron
			y = isPoint ? roi.getYBase() : center[1];
			t = roi.getTPosition();
			d = isPoint ? 0.5 : Math.sqrt(roi.getFloatWidth()*roi.getFloatHeight());
			managerIdx = roiManagerIndex;
		}
		public int getSpotID() {return this.spotID;}
		public int getTrackID() {return this.trackID;}
		public String getLineageID() {return this.lineageID;}
		public String getRoiName() {return this.roiName;}
		public String getRoiLabel() {return this.roiLabel;}
		public String getLinkString() {return this.linkString;}
		public double getLinkCost() {return this.linkCost;}
		public double getX() {return this.x;}
		public double getY() {return this.y;}
		public double getT() {return this.t;}
		public int getManagerIdx() {return this.managerIdx;}
		public Roi getRoi() {return this.roi;}
		
		public void setSpotID(int spotID) { this.spotID = spotID; }
		public void setTrackID(int trackID) { this.trackID = trackID; }
		public void setLineageID(String lineageID) { this.lineageID = lineageID; }
		public void setRoiName(String roiName) { this.roiName = roiName; }
		public void setRoiLabel(String roiLabel) { this.roiLabel = roiLabel; }
		public void setLinkString(String linkString) { this.linkString = linkString; }
		public void setLinkCost(double linkCost) { this.linkCost = linkCost; }
		
		public int getSourceID() {
			if (this.linkString==null || this.linkString.length()==0) return -1;
			int idx = this.linkString.indexOf(pointer);
			if (idx!=-1) return Integer.valueOf(this.linkString.substring(0, idx));
			return -1;
		}
	}



	/*
	ArrayList<Integer> trackIDs = new ArrayList<Integer>();
	//ArrayList<ArrayList<String>> lineageIDs = new ArrayList<ArrayList<String>>();
	for (r in spotRois) {
		if (!trackIDs.contains(r.getTrackID())) {
			trackIDs.add(r.getTrackID());
		}
	}
	println(trackIDs);
	*/
	// update roiName in the saved all ROIs 
	public void updateRoiNameInAllRois (spotRoi spot, int index) {
		if (allRois==null || allRois.length<index || spot==null) return;
		allRois[index].setName(spot.getRoiName());
	}
	// get original AllRoi index 
	public int getAllRoiManagerIndex (spotRoi spot) {
		int index = -1;
		if (allRois==null || spot==null) return index;
		for (int i=0; i<allRois.length; i++) {
			if (allRois[i].getName().equals(spot.getRoiName())) { index = i; break; }
		}
		return index;
	}
	// get current ROI Manager index of spotRoi
	public int getCurrentManagerIndex (spotRoi spot) {
		int index = -1;
		if (rm==null || rm.getCount()==0 || spot==null) return index;
		for (int i=0; i<rm.getCount(); i++) {
			if (rm.getName(i).equals(spot.getRoiName())) { index = i; break; }
		}
		return index;
	}
	// get spot from ROI name
	public void updateLinkInfoToSpotRois () {			
		if (spotRois==null || table==null) return;
		double[] IDs = table.getColumnAsDoubles( table.getColumnIndex( "Spot ID" ) );
		//double start = System.currentTimeMillis();
		//int nSpot = spotRois.size();
		for (spotRoi sr : spotRois) {
			int spotID = sr.getSpotID();
			for (int i=0; i<IDs.length; i++) {
				if (IDs[i] == spotID) {
					int sourceID = (int) table.getValue("Source Spot ID", i);
					double cost = table.getValue("link Cost", i);
					if (sourceID==-1 || cost==-1) continue;
					sr.setLinkString(""+sourceID + pointer + spotID);
					sr.setLinkCost(cost);
				}
			}
		}
		//double duration = (System.currentTimeMillis() - start) / 1000;
		//System.out.println("num spotRois" + nSpot + ", update link info takes: " + duration + " seconds");
	}
	public spotRoi getSpotFromRoiName (String roiName) {
		if (spotRois==null || roiName==null || roiName=="") return null;
		for (spotRoi sr : spotRois) {
			if (sr.getRoiName().equals(roiName))
				return sr;
		}
		return null;
	}
	// get target spot from source spot (into a arraylist)
	public ArrayList<spotRoi> getTargetSpotsFromSource (spotRoi sourceSpot) {
		if (spotRois==null || sourceSpot==null) return null;
		int sourceSpotID = sourceSpot.getSpotID();
		ArrayList<spotRoi> targetList = new ArrayList<spotRoi>();
		for (spotRoi sr : spotRois) {
			if (sr.getSourceID() == sourceSpotID) targetList.add(sr);
		}
		if (targetList.size()==0) return null;
		return targetList;
	}
	// get source/target spot from link string name
	public spotRoi getSourceSpotFromLinkString (String linkString) {
		if (spotRois==null || linkString==null || linkString=="") return null;
		int sourceSpotID = Integer.valueOf(linkString.substring(0, linkString.indexOf(pointer)));
		if (sourceSpotID<=0) return null;
		for (spotRoi sr : spotRois) {
			if (sr.getSpotID() == sourceSpotID)
				return sr;
		}
		return null;
	}
	public spotRoi getTargetSpotFromLinkString (String linkString) { // linkString belongs to target spot
		if (spotRois==null || linkString==null || linkString=="") return null;
		int targetSpotID = Integer.valueOf(linkString.substring(linkString.indexOf(pointer)+pointer.length(), linkString.length()));
		if (targetSpotID<=0) return null;
		for (spotRoi sr : spotRois) {
			if (sr.getSpotID() == targetSpotID)
				return sr;
		}
		return null;
	}
	
	// get spot ROI name into array from arraylist of spotRoi
	public String[] getRoiNameArray (ArrayList<spotRoi> spotList) {
		if (spotList==null || spotList.size()==0) return new String[]{""};
		int size = spotList.size();
		String[] stringArray = new String[size];
		//stringArray[0] = "";
		for (int i=0; i<size; i++) {
			stringArray[i] = spotList.get(i).getRoiName();
		}
		return stringArray;
	}
	// locate nearby spots
	public ArrayList<spotRoi> getNearbySpots (spotRoi sourceSpot, spotRoi targetSpot) {
		if (sourceSpot==null || spotRois==null) return null;
		//double start = System.currentTimeMillis();
		double x1 = sourceSpot.getX(); double y1 = sourceSpot.getY(); double t1 = sourceSpot.getT();
		ArrayList<spotRoi> nearbySpots = new ArrayList<spotRoi>();
		ArrayList<Double> dists = new ArrayList<Double>();
		for (spotRoi sr : spotRois) {
			if (sr==targetSpot) continue;
			if (sr.getT() <= t1) continue;
			if (sr.getT() > t1+2) continue; // check frame t1+1, t1+2
			double x2 = sr.getX(); double y2 = sr.getY(); double t2 = sr.getT();
			double dist = (Math.pow(x1-x2, 2) + Math.pow(y1-y2, 2)) * ( t2-t1 ) ;
			if (dist >= 1000) continue;	// check within sqrt(2000) pixel range
			nearbySpots.add(sr);
			dists.add(dist);
		}
		Collections.sort(nearbySpots, new Comparator<spotRoi>() {
			public int compare(spotRoi spot1, spotRoi spot2) {
				Double dist1 = dists.get(nearbySpots.indexOf(spot1));
				Double dist2 = dists.get(nearbySpots.indexOf(spot2));
				return dist1.compareTo(dist2);
		}});
		nearbySpots.add(0, targetSpot);
		//double duration = (System.currentTimeMillis() - start) / 1000;
		//System.out.println("get neighbour takes " + duration + " seconds");
		return nearbySpots;
	}
	// get sourceString into array from arraylist of spotRoi
	public String[] getSourceStringArray (ArrayList<spotRoi> spotList) {
		if (spotList==null || spotList.size()==0) return new String[]{""};
		int size = spotList.size();
		String[] stringArray = new String[size+1];
		stringArray[0] = "";
		for (int i=0; i<size; i++) {
			stringArray[i+1] = "" + spotList.get(i).getSourceID();
		}
		return stringArray;
	}
	// get linkString into array from arraylist of spotRoi
	public String[] getLinkStringArray (ArrayList<spotRoi> spotList) {
		if (spotList==null || spotList.size()==0) return new String[]{""};
		int size = spotList.size();
		String[] stringArray = new String[size+1];
		stringArray[0] = "";
		for (int i=0; i<size; i++) {
			stringArray[i+1] = spotList.get(i).getLinkString();
		}
		return stringArray;
	}
	// get high link cost spot into arraylist
	public ArrayList<spotRoi> getHighCostSpotsInTrack (ArrayList<spotRoi> spotRois, int trackID) {
		ArrayList<spotRoi> highCostSpots = new ArrayList<spotRoi>();
		for (spotRoi spotRoi : spotRois) {
			if (spotRoi.getTrackID()==trackID)
				if (spotRoi.getLinkCost()>100)
					highCostSpots.add(spotRoi);
		}
		Collections.sort(highCostSpots, new Comparator<spotRoi>() {
			public int compare(spotRoi spot1, spotRoi spot2) {
				Double cost1 = spot1.getLinkCost();
				Double cost2 = spot2.getLinkCost();
				return cost2.compareTo(cost1);
		}});
		return highCostSpots;
	}
	// get largest track ID 
	public int getMaxTrackID (ArrayList<spotRoi> spotRois) {
		int maxID = -1;
		for (spotRoi spotRoi : spotRois) {
			maxID = Math.max(maxID,  spotRoi.getTrackID());
		}
		return maxID;
	}
	// get track IDs from a collection of spot rois (from ROI Manager)
	public ArrayList<Integer> getTrackIDs (ArrayList<spotRoi> spotRois) {
		ArrayList<Integer> trackIDs = new ArrayList<Integer>();
		for (spotRoi spotRoi : spotRois) {
			if (!trackIDs.contains(spotRoi.getTrackID()))
				trackIDs.add(spotRoi.getTrackID());
		}
		return trackIDs;
	}
	// get spot rois of specific track (by track ID)
	public ArrayList<spotRoi> getTrackRois (ArrayList<spotRoi> spotRois, int trackID) {
		ArrayList<spotRoi> trackRois = new ArrayList<spotRoi>();
		for (spotRoi spotRoi : spotRois) {
			if (spotRoi.getTrackID()==trackID)
				trackRois.add(spotRoi);
		}
		return trackRois;
	}
	// get lineage ID from track spot rois
	public ArrayList<String> getLineages (ArrayList<spotRoi> trackRois) {
		ArrayList<String> trackLineage = new ArrayList<String>();
		for (spotRoi trackRoi : trackRois) {
			if (!trackLineage.contains(trackRoi.getLineageID()))
				trackLineage.add(trackRoi.getLineageID());
		}
		return trackLineage;
	}
	// get generation information from lineage ID
	public int getGeneration(String lineageID) {
		return lineageID.length();
	}
	// get parent lineage ID from lineage ID
	public String getParent(String lineageID) {
		if (lineageID.length()==0) return null;
		return lineageID.substring(0, lineageID.length()-1);
	}
	// get sibling lineage ID from lineage ID
	public String getSibling(String lineageID) {
		if (lineageID.length()==0) return null;
		if (lineageID.endsWith("0"))
			return getParent(lineageID)+"1";
		else
			return getParent(lineageID)+"0";
	}
	// get daughter 0 lineage ID from lineage ID
	public String getDaughter0(String lineageID) {
		return lineageID+"0";
	}
	// get daughter 1 lineage ID from lineage ID
	public String getDaughter1(String lineageID) {
		return lineageID+"1";
	}
	// get all predeccessors
	public ArrayList<String> getAllPredecessors(String lineageID) {
		ArrayList<String> allPredecessors = new ArrayList<String>();
		while (lineageID!=null) {
			lineageID = getParent(lineageID);
			allPredecessors.add(lineageID);
		}
		Collections.reverse(allPredecessors);
		return allPredecessors;
	}
	// get all succsessors
	public ArrayList<String> getAllSuccessors(
			ArrayList<spotRoi> trackRois, 
			String lineageID
			) {
		ArrayList<String> allSuccessors = new ArrayList<String>();
		String daughter0 = getDaughter0(lineageID);
		String daughter1 = getDaughter1(lineageID);
		boolean daughter0Exist = lineageExist(trackRois, daughter0);
		boolean daughter1Exist = lineageExist(trackRois, daughter1);
		if (daughter0Exist) {
			allSuccessors.add(daughter0);
			allSuccessors.addAll(getAllSuccessors(trackRois, daughter0));
		}
		if (daughter1Exist) {
			allSuccessors.add(daughter1);
			allSuccessors.addAll(getAllSuccessors(trackRois, daughter1));
		}
		return allSuccessors;
	}
	// check if a certain lineage exist in track spot rois
	public boolean lineageExist(ArrayList<spotRoi> trackRois, String lineage) {
		ArrayList<String> trackLineage = getLineages(trackRois);
		return (trackLineage.contains(lineage));
	}
	// get all spot Rois belonging to the specific lineage ID, sort by ascending time
	public ArrayList<spotRoi> getLineageSpotRois (
		ArrayList<spotRoi> trackRois,
		String lineageID
		) {
		ArrayList<spotRoi> lineageSpotRois = new ArrayList<spotRoi>();
		for (spotRoi trackRoi : trackRois) {
			if (trackRoi.getLineageID().equals(lineageID))
				lineageSpotRois.add(trackRoi);
		}
		Collections.sort(lineageSpotRois, new Comparator<spotRoi>() {
		    @Override
		    public int compare(spotRoi sr1, spotRoi sr2) {
		        return Double.compare(sr1.getT(), sr2.getT());
		    }
		});
		return lineageSpotRois;
	}
	// get spot Roi immediate predecessor if exist, based on frame comparator
	public ArrayList<spotRoi> removeFromCurrent(
			ArrayList<spotRoi> lineageSpotRois,
			spotRoi currentSpotRoi,
			boolean removeOnwards
			) {
		ArrayList<spotRoi> lineageSpotRoisClone = (ArrayList<spotRoi>) lineageSpotRois.clone();
		Collections.sort(lineageSpotRoisClone, new Comparator<spotRoi>() {
		    @Override
		    public int compare(spotRoi sr1, spotRoi sr2) {
		        return Double.compare(sr1.getT(), sr2.getT());
		    }
		});
		int idx1 = lineageSpotRois.indexOf(currentSpotRoi);
		int idx2 = lineageSpotRois.size();
		ArrayList<spotRoi> remove = new ArrayList<spotRoi>();
		if (removeOnwards) {
			for (int idx = idx1+1; idx<idx2; idx++) {
				remove.add(lineageSpotRoisClone.get(idx));
			}	
		} else {
			for (int idx = 0; idx<idx1; idx++) {
				remove.add(lineageSpotRoisClone.get(idx));
			}
		}
		lineageSpotRoisClone.removeAll(remove);
		return lineageSpotRoisClone;
	}
	// reconstruct lineage tree from current spot backwards
	public ArrayList<spotRoi> getLineageBackward (
			ArrayList<spotRoi> trackRois,
			spotRoi currentSpotRoi
			) {
		ArrayList<spotRoi> lineageBack = new ArrayList<spotRoi>();	
		String startLineage = currentSpotRoi.getLineageID();
		ArrayList<String> allPredecessors = getAllPredecessors(startLineage);	
		for (String predecessor : allPredecessors) {
			lineageBack.addAll(getLineageSpotRois(trackRois, predecessor));
		}
		ArrayList<spotRoi> currentLineage = getLineageSpotRois(trackRois, startLineage);
		ArrayList<spotRoi> temp = removeFromCurrent(currentLineage, currentSpotRoi, true);
		lineageBack.addAll(temp);
		return lineageBack;
	}
	// reconstruct lineage tree from current spot onwards
	public ArrayList<spotRoi> getLineageForward (
			ArrayList<spotRoi> trackRois,
			spotRoi currentSpotRoi
			) {
		ArrayList<spotRoi> lineageForward = new ArrayList<spotRoi>();	
		String startLineage = currentSpotRoi.getLineageID();

		ArrayList<spotRoi> currentLineage = getLineageSpotRois(trackRois, startLineage);
		ArrayList<spotRoi> temp = removeFromCurrent(currentLineage, currentSpotRoi, false);
		lineageForward.addAll(temp);

		ArrayList<String> allSuccessors = getAllSuccessors(trackRois, startLineage);
		for (String successor : allSuccessors) {
			lineageForward.addAll(getLineageSpotRois(trackRois, successor));
		}
		return lineageForward;
	}

	// get sorted track IDs from a collection of spot rois (from ROI Manager)
	public ArrayList<Integer> getSortedTrackIDs (
			ArrayList<spotRoi> spotRois,
			int sortOption	// 0:ID, 1:length, 2:start, 3:#lineage, 4:#ROI, 5:#mitosis
			) {
		ArrayList<Integer> trackIDs = getTrackIDs(spotRois);
		switch (sortOption) {
		case 0:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					return ID1.compareTo(ID2);
			}});
			break;
		case 1:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					ArrayList<spotRoi> trackRoi1 = getTrackRois (spotRois, ID1);
					ArrayList<spotRoi> trackRoi2 = getTrackRois (spotRois, ID2);
					double tMax1 = 0.0; double tMax2 = 0.0;
					for (spotRoi sr : trackRoi1) {
						tMax1 = Math.max(sr.t, tMax1);
					}
					for (spotRoi sr : trackRoi2) {
						tMax2 = Math.max(sr.t, tMax2);
					}
					Integer length1 = (int)(tMax1 - trackRoi1.get(0).t);
					Integer length2 = (int)(tMax2 - trackRoi2.get(0).t);
					return length2.compareTo(length1);
			}});
			break;
		case 2:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					ArrayList<spotRoi> trackRoi1 = getTrackRois (spotRois, ID1);
					ArrayList<spotRoi> trackRoi2 = getTrackRois (spotRois, ID2);
					Integer start1 = (int)(trackRoi1.get(0).t);
					Integer start2 = (int)(trackRoi2.get(0).t);
					return start1.compareTo(start2);
			}});
			break;
		case 3:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					ArrayList<spotRoi> trackRoi1 = getTrackRois (spotRois, ID1);
					ArrayList<spotRoi> trackRoi2 = getTrackRois (spotRois, ID2);
					
					Integer numLineage1 = getLineages(trackRoi1).size();
					Integer numLineage2 = getLineages(trackRoi2).size();
					return numLineage2.compareTo(numLineage1);
			}});
			break;
		case 4:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					ArrayList<spotRoi> trackRoi1 = getTrackRois (spotRois, ID1);
					ArrayList<spotRoi> trackRoi2 = getTrackRois (spotRois, ID2);
					Integer numRoi1 = trackRoi1.size();
					Integer numRoi2 = trackRoi2.size();
					return numRoi2.compareTo(numRoi1);
			}});
			break;
		case 5:
			Collections.sort(trackIDs, new Comparator<Integer>() {
				@Override
				public int compare(Integer ID1, Integer ID2) {
					ArrayList<spotRoi> trackRoi1 = getTrackRois (spotRois, ID1);
					ArrayList<spotRoi> trackRoi2 = getTrackRois (spotRois, ID2);
					Integer numLabel1 = 0;
					for (spotRoi sr : trackRoi1) {
						if (sr.getRoiLabel().length()!=0)
							numLabel1++;
					}
					Integer numLabel2 = 0;
					for (spotRoi sr : trackRoi2) {
						if (sr.getRoiLabel().length()!=0)
							numLabel2++;
					}
					return numLabel2.compareTo(numLabel1);
			}});
			break;
		}
		return trackIDs;
	}
	
	/*
	 *  2020.04.09: remove link; exchange target //THIS!!!
	 */
	public void removeLink (ArrayList<spotRoi> spotRois, spotRoi targetSpot) {
		// locate all rest lineage spot, from current spot
		ArrayList<spotRoi> trackSpots = getTrackRois (spotRois, targetSpot.getTrackID());
		ArrayList<spotRoi> lineageForward = getLineageForward (trackSpots, targetSpot);
		int oldTrackID = targetSpot.getTrackID();
		int newTrackID = getMaxTrackID(spotRois) + 1;
		//String oldLineageSeg = targetSpot.getLineageID();
		int lineLen = targetSpot.getLineageID().length();
		
		for (spotRoi spot : lineageForward) {
			int currentRMIndex = getCurrentManagerIndex(spot);
			int allIndex = getAllRoiManagerIndex(spot);
			if (spot.equals(targetSpot)) { // remove link string and cost
				spot.setLinkString("");
				spot.setLinkCost(-1);
			}
			// give new trackID, being the start spot
			String newLineageID = spot.getLineageID().substring(lineLen, spot.getLineageID().length());
			String newRoiName = spot.getRoiName().replace("ID:"+oldTrackID, "ID:"+newTrackID);
			newRoiName = newRoiName.replace("-"+spot.getLineageID(), "-"+newLineageID);
			spot.setTrackID(newTrackID);
			spot.setLineageID(newLineageID);
			spot.setRoiName(newRoiName);
			// update all ROI name
			if (currentRMIndex!=-1) rm.rename(currentRMIndex, newRoiName);
			updateRoiNameInAllRois (spot, allIndex);
		}
	}
	public void addLink (ArrayList<spotRoi> spotRois, spotRoi sourceSpot, spotRoi newTargetSpot) {
		// locate all rest lineage spot, from current spot
		ArrayList<spotRoi> trackSpots = getTrackRois (spotRois, newTargetSpot.getTrackID());
		ArrayList<spotRoi> lineageForward = getLineageForward (trackSpots, newTargetSpot);
		int oldTrackID = newTargetSpot.getTrackID();
		int newTrackID = sourceSpot.getTrackID();
		int lineLen = newTargetSpot.getLineageID().length();
		for (spotRoi spot : lineageForward) {
			//System.out.println("\n\ndebug spot roiName: " + spot.getRoiName());
			int currentRMIndex = getCurrentManagerIndex(spot);
			int allIndex = getAllRoiManagerIndex(spot);
			
			if (spot.equals(targetSpot)) { // update link string and cost
				spot.setLinkString(""+sourceSpot.getSpotID() + pointer + newTargetSpot.getSpotID());
				spot.setLinkCost(0);
			}
			// give new trackID, being the start spot
			String newLineageID = sourceSpot.getLineageID() + spot.getLineageID().substring(lineLen, spot.getLineageID().length());
			String newRoiName = spot.getRoiName().replace("ID:"+oldTrackID, "ID:"+newTrackID);
			newRoiName = newRoiName.replace("-"+spot.getLineageID(), "-"+newLineageID);
			
			spot.setTrackID(newTrackID);
			spot.setLineageID(newLineageID);
			spot.setRoiName(newRoiName);
			
			// update all ROI name
			if (currentRMIndex!=-1) rm.rename(currentRMIndex, newRoiName);
			updateRoiNameInAllRois (spot, allIndex);
		}
		
		
	}

		/*
		 * Add a new spot to current lineage, follow by all of its successors
		 */
		public void addLineageToLineage(
				ArrayList<spotRoi> spotRois,
				spotRoi currentSpotRoi,
				spotRoi targetSpotRoi,
				boolean replaceExist // if false, then add as a daughter cell lineage
				) {
			ArrayList<spotRoi> sourceTrackRois = getTrackRois (spotRois, currentSpotRoi.getTrackID());
			ArrayList<spotRoi> targetTrackRois = getTrackRois (spotRois, targetSpotRoi.getTrackID());  
			// get current lineage backwards
			ArrayList<spotRoi> sourcelineageBackwards = getLineageBackward(sourceTrackRois, currentSpotRoi);
			ArrayList<spotRoi> sourcelineageForwards = getLineageForward(sourceTrackRois, currentSpotRoi);
			// get target lineage onwards
			ArrayList<spotRoi> targetlineageForwards = getLineageForward(targetTrackRois, targetSpotRoi);
			// make a link between current spotRoi and target spotRoi
			//!!! HERE!
			String sourceLineageID = currentSpotRoi.getLineageID();
			String targetLineageID = targetSpotRoi.getLineageID();
			int nGenTarget = targetLineageID.length();
			ArrayList<String> newLineageID = new ArrayList<String>();
			for (spotRoi sr : targetlineageForwards) {
				String newID = sr.getLineageID();
				if (nGenTarget !=0) {
					newID = sourceLineageID + newID.substring(nGenTarget, newID.length());
				}
				//newID = newID.substring(targetLineageID.length(), newID.length()-1);
				//IJ.log(newID);
				//newLineageID = targetLine
				IJ.log("ROI:" + sr.getSpotID() + ", lineage:"+newID);
			}
			if (replaceExist) {
				// remove old, add new
			} else {
				spotRoi nextInSource = sourcelineageForwards.get(1);
				
				
				// check if next spot is a daughter
				
				// add new on the side of old
			}
			// update target lineage spotRoi labels
		}

		public String getLineageInfo(
				ArrayList<spotRoi> spotRois,
				int trackID,
				String lineageID
				) {
			String trackInfo = "Track:\n" + String.valueOf(trackID);
			ArrayList<String> allPredecessors = getAllPredecessors(lineageID);
			String lineageInfo = "Lineage:\n";
			for (String predecessor : allPredecessors) {
				if (predecessor !=null) {
					if (predecessor.equals("")) lineageInfo += "-root\n";
					else lineageInfo += "-"+predecessor+"\n";
				}
			}
			if (!lineageID.equals("")) lineageInfo += "-"+lineageID;
			else lineageInfo += "-root";
			return (trackInfo + "\n" + 	lineageInfo);
			
		}
		
		public Roi[] getRoiArrayFromTrack(
				ArrayList<spotRoi> spotRois,
				int trackID
				) {
			ArrayList<spotRoi> trackRois = getTrackRois(spotRois, trackID);
			Roi[] trackRoiArray = new Roi[trackRois.size()];
			for (int i=0; i<trackRois.size(); i++) {
				trackRoiArray[i] = trackRois.get(i).getRoi();
			}
			return trackRoiArray;
		}
		public Roi[] getRoiArrayFromLineage(
				ArrayList<spotRoi> spotRois,
				int trackID,
				String lineageID
				) {
			ArrayList<spotRoi> trackRois = getTrackRois(spotRois, trackID);
			ArrayList<spotRoi> temp = getLineageSpotRois(trackRois, lineageID);
			int size = temp.size();
			ArrayList<spotRoi> lineageRois = getLineageBackward (trackRois, temp.get(size-1));
			Roi[] lineageRoiArray = new Roi[lineageRois.size()];
			for (int i=0; i<lineageRois.size(); i++) {
				lineageRoiArray[i] = lineageRois.get(i).getRoi();
			}
			return lineageRoiArray;
		}
		
		
		// Main function to configure the GUI
		@SuppressWarnings({ "unchecked", "rawtypes" })
		public void addTrackPanelToFrame(PlugInFrame f) {
			
			selectedTrack = 0;
			selectedLineage = "";
			trackIDArray = new Integer[1]; trackIDArray[0] = selectedTrack;
			lineageArray = new String[1]; lineageArray[0] = selectedLineage;
			//trackedCells = new ArrayList<ArrayList<Roi>>();
			
			if (rm!=null) {
				int nROI = rm.getCount();
				allRois = rm.getRoisAsArray();

				spotRois = new ArrayList<spotRoi>();
				for (int i=0; i<nROI; i++) {
					spotRois.add(new spotRoi(i));
				}
				trackIDs = getTrackIDs(spotRois);
				trackIDArray = trackIDs.toArray(new Integer[trackIDs.size()]);
				trackRois = getTrackRois(spotRois, selectedTrack);
				lineage = getLineages(trackRois);
				lineageArray = lineage.toArray(new String[lineage.size()]);
			}
			
			
			JPanel parentPanel = new JPanel();
			parentPanel.setBorder(border);
			parentPanel.setBackground(f.getBackground());
			parentPanel.setLayout(new BoxLayout(parentPanel, BoxLayout.Y_AXIS));
			
			
			/*
			JPanel contentPanel = new JPanel();
			contentPanel.setBorder(border);
			contentPanel.setBackground(panelColor);
			contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
			*/
			
			/*
			 * construct the track selection panel
			 */
			JPanel trackSelection = new JPanel();
			trackSelection.setBorder(border);
			trackSelection.setBackground(panelColor);
			trackSelection.setLayout(new BoxLayout(trackSelection, BoxLayout.Y_AXIS));
			//trackSelection.setMaximumSize(new Dimension(400, 300));
		
			lineageInfo = new JTextArea(10, 100); 
			lineageInfo.setEditable(false);
			lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
			trackSelection.add(lineageInfo);
			lineageInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			JPanel selectionPanel = new JPanel();
			selectionPanel.setBackground(panelColor);
			selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.X_AXIS));
			JLabel trackSelectionTitle = new JLabel("Track");
			//trackSelectionTitle.setFont(panelTitleFont);
			trackSelectionTitle.setBorder(border);
			trackSelectionTitle.setForeground(Color.BLACK);
			trackSelectionTitle.setHorizontalTextPosition(JLabel.LEFT);
			trackSelectionTitle.setVerticalTextPosition(JLabel.CENTER);
			JLabel lineageSelectionTitle = new JLabel("Lineage");
			//lineageSelectionTitle.setFont(panelTitleFont);
			lineageSelectionTitle.setBorder(border);
			lineageSelectionTitle.setForeground(Color.BLACK);
			lineageSelectionTitle.setHorizontalTextPosition(JLabel.LEFT);
			lineageSelectionTitle.setVerticalTextPosition(JLabel.CENTER);
			lineageList = new JComboBox();
			lineageList.setModel(new DefaultComboBoxModel(lineageArray));
			lineageList.setSelectedIndex(0);
			lineageList.setMaximumSize(new Dimension(200, 30));
			lineageList.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			        selectedLineage = (String) lineageList.getSelectedItem(); 
			        lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
			    }
			});
			trackList = new JComboBox(trackIDArray);
			trackList.setSelectedIndex(0);
			trackList.setMaximumSize(new Dimension(150, 30));
			trackList.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			        selectedTrack = (int) trackList.getSelectedItem();
			        trackRois = getTrackRois(spotRois, selectedTrack);
			        lineage = getLineages(trackRois);
			        lineageArray = lineage.toArray(new String[lineage.size()]);
			        lineageList.setModel(new DefaultComboBoxModel(lineageArray));
			        
			        ArrayList<spotRoi> highCostSpots = getHighCostSpotsInTrack (spotRois, selectedTrack);
			        linkArray = getLinkStringArray (highCostSpots);
			        linkList.setModel(new DefaultComboBoxModel(linkArray));
			        
			        sourceArray = getSourceStringArray (highCostSpots);
			        sourceList.setModel(new DefaultComboBoxModel(sourceArray));
			    }
			});
			selectionPanel.add(trackSelectionTitle); trackSelectionTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			selectionPanel.add(trackList); trackList.setAlignmentY(Component.CENTER_ALIGNMENT);
			selectionPanel.add(lineageSelectionTitle); lineageSelectionTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			selectionPanel.add(lineageList); lineageList.setAlignmentY(Component.CENTER_ALIGNMENT);
			trackSelection.add(selectionPanel);
			selectionPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			
			JPanel sortPanel = new JPanel();
			sortPanel.setBorder(new EmptyBorder(new Insets(5, 5, 5, 155)));
			//sortPanel.setBorder(border);
			sortPanel.setBackground(panelColor);
			sortPanel.setLayout(new BoxLayout(sortPanel, BoxLayout.X_AXIS));
			JLabel sortOptionTitle = new JLabel("sort by");
			sortOptionTitle.setBorder(new EmptyBorder(new Insets(0, 0, 0, 5)));
			sortOptionTitle.setForeground(Color.BLACK);
			sortOptionTitle.setHorizontalTextPosition(JLabel.LEFT);
			sortOptionTitle.setVerticalTextPosition(JLabel.CENTER);
			sortTracks = new JComboBox(sortOptions);
			sortTracks.setSelectedIndex(0);
			sortTracks.setMaximumSize(new Dimension(75, 30));
			sortTracks.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			        int sortOption = sortTracks.getSelectedIndex();
			        ArrayList<Integer> sortedTracks;
			        DefaultComboBoxModel model = new DefaultComboBoxModel(trackIDArray);
			        switch (sortOption) {
			        case 0:
			        	if (trackIDArray_ID == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 0);
			        		trackIDArray_ID = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_ID);
			        	break;
			        case 1:
			        	if (trackIDArray_length == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 1);
			        		trackIDArray_length = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_length);
			        	break;
			        case 2: //"length", "start", "#lineage", "#ROI", "#mitosis"};
			        	if (trackIDArray_start == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 2);
			        		trackIDArray_start = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_start);
			        	break;
			        case 3:
			        	if (trackIDArray_lineage == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 3);
			        		trackIDArray_lineage = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_lineage);
			        	break;
			        case 4:
			        	if (trackIDArray_ROI == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 4);
			        		trackIDArray_ROI = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_ROI);
			        	break;
			        case 5:
			        	if (trackIDArray_mitosis == null) {
			        		sortedTracks = getSortedTrackIDs(spotRois, 5);
			        		trackIDArray_mitosis = sortedTracks.toArray(new Integer[sortedTracks.size()]);
			        	}
			        	model = new DefaultComboBoxModel(trackIDArray_mitosis);
			        	break;
			        }
			        trackList.setModel(model);
			        trackList.setSelectedIndex(0);
			        selectedTrack = (int) trackList.getSelectedItem();
			        selectedLineage = "";
			    }
			});
			// configure button taken from Manager
			JButton btnSelectFromManager = new JButton("select current");
			btnSelectFromManager.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	int idx = rm.getSelectedIndex();
			    	if (idx==-1) return;
			    	spotRoi selectedSpotRoi = new spotRoi(idx);
			    	selectedTrack = selectedSpotRoi.getTrackID();
			    	selectedLineage = selectedSpotRoi.getLineageID();
			    	trackList.setSelectedItem(selectedTrack);
			    	lineageList.setSelectedItem(selectedLineage);
			        lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
			    }
			});
			sortPanel.add(sortOptionTitle); sortOptionTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			sortPanel.add(sortTracks); sortTracks.setAlignmentY(Component.CENTER_ALIGNMENT);
			sortPanel.add(btnSelectFromManager); btnSelectFromManager.setAlignmentY(Component.CENTER_ALIGNMENT);
			trackSelection.add(sortPanel);
			sortPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

			/*
			 * construct the button panel
			 */
			JPanel buttonPanel = new JPanel();
			//buttonPanel.setBorder(border);
			//buttonPanel.setBackground(panelColor);
			//buttonPanel.setLayout(new BoxLayout(buttonPanel, BoxLayout.Y_AXIS));
			
			JButton btnShowLineage = new JButton("show lineage");
			JButton btnShowTrack = new JButton("show track");
			JButton btnShowManager = new JButton("show all");
			JButton btnRefreshManager = new JButton("refresh");
			/*
			btnShowLineage.setMaximumSize(buttonSize);
			btnShowTrack.setMaximumSize(buttonSize);
			btnShowManager.setMaximumSize(buttonSize);
			btnRefreshManager.setMaximumSize(buttonSize);
			*/
			
			GroupLayout buttonLayout = new GroupLayout(buttonPanel);
			buttonPanel.setLayout(buttonLayout);
			buttonLayout.setAutoCreateGaps(true);
			buttonLayout.setAutoCreateContainerGaps(true);

			buttonLayout.setHorizontalGroup(buttonLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
				 .addGroup(buttonLayout.createSequentialGroup()
				    .addComponent(btnShowLineage)
				    .addComponent(btnShowTrack))
				 .addGroup(buttonLayout.createSequentialGroup()
				    .addComponent(btnShowManager)
				    .addComponent(btnRefreshManager)));
				 	
			buttonLayout.linkSize(SwingConstants.HORIZONTAL, btnShowLineage, btnShowManager, btnShowTrack, btnRefreshManager);	

			buttonLayout.setVerticalGroup(buttonLayout.createSequentialGroup()
				.addGroup(buttonLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
					.addGroup(buttonLayout.createSequentialGroup()
			                .addComponent(btnShowLineage)
			                .addComponent(btnShowManager))
					.addGroup(buttonLayout.createSequentialGroup()
			                .addComponent(btnShowTrack)
			                .addComponent(btnRefreshManager))));
			
			//buttonPanel.setBorder(border);
			buttonPanel.setBackground(panelColor);
			//buttonPanel.setMaximumSize(panelMax);
			
			/*
			buttonPanel.add(btnShowLineage); btnShowLineage.setAlignmentX(Component.LEFT_ALIGNMENT);
			buttonPanel.add(btnShowTrack); btnShowTrack.setAlignmentX(Component.LEFT_ALIGNMENT);
			buttonPanel.add(btnShowManager); btnShowManager.setAlignmentX(Component.LEFT_ALIGNMENT);
			buttonPanel.add(btnRefreshManager); btnRefreshManager.setAlignmentX(Component.LEFT_ALIGNMENT);
			 */
			
			btnShowLineage.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
					//Roi[] rois = rm.getRoisAsArray();
			    	selectedRois = getRoiArrayFromLineage(spotRois, selectedTrack, selectedLineage);
					//rm.setVisible(false);
					rm.reset();
					for (int i=0; i<selectedRois.length; i++) {
						rm.add(imp, selectedRois[i], -1);
					}
					rm.runCommand("Show All");
			    }
			});
			
			btnShowTrack.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
					//Roi[] rois = rm.getRoisAsArray();
			    	selectedRois = getRoiArrayFromTrack(spotRois, selectedTrack);
			    	rm.reset();
					for (int i=0; i<selectedRois.length; i++) {
						rm.add(imp, selectedRois[i], -1);
					}
					rm.runCommand("Show All");
			    }
			});
			
			btnShowManager.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
					rm.reset();
					for (int i=0; i<allRois.length; i++) {
						rm.add(imp, allRois[i], -1);
					}
					rm.runCommand("Show All");
			    }
			});
			btnRefreshManager.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	refreshTrackManager();
			    	/*
			    	imp = WindowManager.getCurrentImage();
			    	rm = RoiManager.getInstance();
					if (imp==null || rm==null) return;
					rm.runCommand("Show All");
					sortROI();
					int nROI = rm.getCount();
					allRois = rm.getRoisAsArray();
					//trackedCells = new ArrayList<ArrayList<Roi>>();
			    	spotRois = new ArrayList<spotRoi>();
					for (int i=0; i<nROI; i++) {
						spotRois.add(new spotRoi(i));
					}
					trackIDs = getTrackIDs(spotRois);
					trackIDArray = trackIDs.toArray(new Integer[trackIDs.size()]);
					trackRois = getTrackRois(spotRois, selectedTrack);
					lineage = getLineages(trackRois);
					lineageArray = lineage.toArray(new String[lineage.size()]);
					trackIDArray_ID = null;			trackIDArray_length = null; 	trackIDArray_start = null;
					trackIDArray_lineage = null;	trackIDArray_ROI = null; 		trackIDArray_mitosis = null;
					//selectedTrack = 0; selectedLineage = "";
					DefaultComboBoxModel modelTrackList = new DefaultComboBoxModel(trackIDArray);
					trackList.setModel(modelTrackList);
			        DefaultComboBoxModel modelCellList = new DefaultComboBoxModel(lineageArray);
			        lineageList.setModel(modelCellList);
			        lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
			        */
			    }
			});
			trackSelection.add(buttonPanel);
			buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			trackSelection.setPreferredSize(panelContentSize);
			trackSelection.setMaximumSize(panelMax);
			
			parentPanel.add(trackSelection);
			trackSelection.setAlignmentX(Component.CENTER_ALIGNMENT);
			parentPanel.setPreferredSize(panelParentSize);
			
			f.add(parentPanel);
			f.pack();	
		}
		
		
		// add correct bad link panel here:
		public void addCorrectionPanelToFrame(PlugInFrame f) {
			JPanel parentPanel = new JPanel();
			parentPanel.setBorder(border);
			parentPanel.setBackground(f.getBackground());
			parentPanel.setLayout(new BoxLayout(parentPanel, BoxLayout.Y_AXIS));
			// correction panel
			JPanel correctPanel = new JPanel();
			correctPanel.setBackground(panelColor);
			correctPanel.setLayout(new BoxLayout(correctPanel, BoxLayout.Y_AXIS));
			
			// 1st button group: load result table; toggle between source and target
			JPanel firstButtonPanel = new JPanel();
			firstButtonPanel.setBorder(border);
			firstButtonPanel.setBackground(panelColor);
			firstButtonPanel.setLayout(new BoxLayout(firstButtonPanel, BoxLayout.X_AXIS));
			// button to load results table (to get linking cost)
			JButton loadResult = new JButton("load table");
			loadResult.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	OpenDialog od = new OpenDialog("Load Results Table");
			    	tablePath = od.getPath();
					if (tablePath == null || !tablePath.toLowerCase().endsWith(".csv"))
						return;
					table = ResultsTable.open2(tablePath);
					rm = RoiManager.getInstance();
					if (table==null || rm==null) return;
					updateLinkInfoToSpotRois(); // refresh the spotRoi array, add cost using current RoiManager and loaded result table;
			    }
			});
			// button to load results table (to get linking cost)
			JButton showResult = new JButton("show table");
			showResult.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	if (table==null) return;
			    	table.showRowIndexes(true);
			    	table.show("Results Table Track Manager");
			    }
			});
			// button to toggle between source and target spot ROI
			JButton toggleButton = new JButton("toggle S-T");
			toggleButton.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	if (sourceSpot==null || targetSpot==null) return;
			    	int sourceIndex = getCurrentManagerIndex(sourceSpot);
			    	//int targetIndex = getCurrentManagerIndex(targetSpot);
			    	if (rm.getSelectedIndex() != sourceIndex) // not selected source, then select source spot
			    		rm.selectAndMakeVisible(imp, getCurrentManagerIndex(sourceSpot));
			    	else
			    		rm.selectAndMakeVisible(imp, getCurrentManagerIndex(targetSpot));
			    }
			});			
			firstButtonPanel.add(loadResult);
			firstButtonPanel.add(showResult);
			firstButtonPanel.add(toggleButton);
			correctPanel.add(firstButtonPanel);
			firstButtonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);

			
			// dropdown list panel
			// two dropdown list: wrong link candidate; correct target candidate
			JPanel sourcePanel = new JPanel();
			sourcePanel.setBackground(panelColor);
			sourcePanel.setLayout(new BoxLayout(sourcePanel, BoxLayout.X_AXIS));
			JPanel linkPanel = new JPanel();
			linkPanel.setBackground(panelColor);
			linkPanel.setLayout(new BoxLayout(linkPanel, BoxLayout.X_AXIS));
			JPanel targetPanel = new JPanel();
			targetPanel.setBackground(panelColor);
			targetPanel.setLayout(new BoxLayout(targetPanel, BoxLayout.X_AXIS));
			JLabel sourceTitle = new JLabel("Source");
			//wrongLinkTitle.setFont(panelTitleFont);
			sourceTitle.setBorder(border);
			sourceTitle.setForeground(Color.BLACK);
			sourceTitle.setHorizontalTextPosition(JLabel.LEFT);
			sourceTitle.setVerticalTextPosition(JLabel.CENTER);
			JLabel linkTitle = new JLabel("Link");
			//wrongLinkTitle.setFont(panelTitleFont);
			linkTitle.setBorder(border);
			linkTitle.setForeground(Color.BLACK);
			linkTitle.setHorizontalTextPosition(JLabel.LEFT);
			linkTitle.setVerticalTextPosition(JLabel.CENTER);
			JLabel targetTitle = new JLabel("Target");
			//candidateTargetTitle.setFont(panelTitleFont);
			targetTitle.setBorder(border);
			targetTitle.setForeground(Color.BLACK);
			targetTitle.setHorizontalTextPosition(JLabel.LEFT);
			targetTitle.setVerticalTextPosition(JLabel.CENTER);
			
			
			// get source, link pair, target into the dropdown lists
			DefaultComboBoxModel model = new DefaultComboBoxModel(linkArray);
			linkList = new JComboBox(linkArray);
			linkList.setModel(model);
			linkList.setSelectedIndex(0);
			linkList.setMaximumSize(new Dimension(200, 30));
			linkList.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			    	//imp.setOverlay(null);
			    	if (linkList.getSelectedIndex()==0) return;
			    	
			    	sourceList.setSelectedIndex(linkList.getSelectedIndex());
			    	
			    	sourceSpot = getSourceSpotFromLinkString ((String) linkList.getSelectedItem());
			    	targetSpot = getTargetSpotFromLinkString ((String) linkList.getSelectedItem());
			    	if (sourceSpot!=null) rm.selectAndMakeVisible(imp, getCurrentManagerIndex(sourceSpot));
			    	//rm.select(highCostSourceSpot.getManagerIdx());
			    	ArrayList<spotRoi> nearBySpots = getNearbySpots (sourceSpot, targetSpot);
			    	targetArray = getRoiNameArray (nearBySpots);
			    	targetList.setModel(new DefaultComboBoxModel(targetArray));
			    }
			});
			sourceList = new JComboBox();
			sourceList.setModel(new DefaultComboBoxModel(sourceArray));
			sourceList.setSelectedIndex(0);
			sourceList.setMaximumSize(new Dimension(200, 30));
			sourceList.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			    	
			    	if (sourceList.getSelectedIndex()==0) {
			    		// set user define source spot
			    		return;
			    	}
			    	
			    	linkList.setSelectedIndex(sourceList.getSelectedIndex());
			    	
			    	sourceSpot = getSourceSpotFromLinkString ((String) linkList.getSelectedItem());

			    	if (sourceSpot==null) return;
			    	rm.selectAndMakeVisible(imp, getCurrentManagerIndex(sourceSpot));
			    	
			    }
			});
			targetList = new JComboBox();
			targetList.setModel(new DefaultComboBoxModel(targetArray));
			targetList.setSelectedIndex(0);
			targetList.setMaximumSize(new Dimension(200, 30));
			targetList.addActionListener(new ActionListener() {
			    @Override
			    public void actionPerformed(ActionEvent event) {
			    	//imp.setOverlay(null);
			    	//if (trackList.getSelectedIndex()==0) return;
			    	//sourceSpot = getSourceSpotFromLinkString ((String) linkList.getSelectedItem());
			    	//if (sourceSpot==null) return;
			    	//Roi sourceRoi = (Roi) (sourceSpot.getRoi()).clone();
			    	//sourceRoi.setPosition(0, 0, 0);
			    	//imp.setOverlay(new Overlay(sourceRoi));
			    	
			    	//if (targetList.getSelectedIndex()==0) return;
			    	selectedSpotRoiName = (String) targetList.getSelectedItem();
			    	targetSpot = getSpotFromRoiName(selectedSpotRoiName);
			    	if (targetSpot!=null)	rm.selectAndMakeVisible(imp, getCurrentManagerIndex(targetSpot));
			    	//rm.select(targetSpot.getManagerIdx());
			    }
			});
			sourcePanel.add(sourceTitle); sourceTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			sourcePanel.add(sourceList); sourceList.setAlignmentY(Component.CENTER_ALIGNMENT);
			linkPanel.add(linkTitle); linkTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			linkPanel.add(linkList); linkList.setAlignmentY(Component.CENTER_ALIGNMENT);
			targetPanel.add(targetTitle); targetTitle.setAlignmentY(Component.CENTER_ALIGNMENT);
			targetPanel.add(targetList); targetList.setAlignmentY(Component.CENTER_ALIGNMENT);
			correctPanel.add(sourcePanel); correctPanel.add(linkPanel); correctPanel.add(targetPanel);
			sourcePanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			linkPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			targetPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			
			// 2nd button group: set source; set target
			JPanel secondButtonPanel = new JPanel();
			secondButtonPanel.setBorder(border);
			secondButtonPanel.setBackground(panelColor);
			secondButtonPanel.setLayout(new BoxLayout(secondButtonPanel, BoxLayout.X_AXIS));
			
			JButton setSource = new JButton("set Source");
			setSource.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	int currentIndex = rm.getSelectedIndex();
			    	if (currentIndex==-1) return;
			    	sourceSpot = getSpotFromRoiName(rm.getName(currentIndex));
			    	
			    	sourceList.setSelectedIndex(0);
			    	//sourceList.setSelectedItem(sourceSpot.getSpotID());
			    	
			    }
			});
			JButton setTarget = new JButton("set Target");
			setTarget.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	int currentIndex = rm.getSelectedIndex();
			    	if (currentIndex==-1) return;
			    	targetSpot = getSpotFromRoiName(rm.getName(currentIndex));
			    }
			});
			JButton autoCorrect = new JButton("auto correct");
			autoCorrect.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	// generic dialog
			    	if (!autoCorrectDialog()) return;
			    	// auto correct
			    	//table = (ResultsTable) result.clone();
			    	if (!table.columnExists( "Source ROI index" )) table = prepareSourceIndexColumn(table);
			    	cellROIArray = allRois;
			    	frameROIs = makeFrameROIs(cellROIArray);
			    	correctLinks(imp, table, cellROIArray, frameROIs, minCost, maxCount, rangeXY, rangeT, minSimScore);
			    	// update all ROI, table, RoiManager
			    	//table.showRowIndexes(true);
			    	//table.show("Results-autoCorrect");
			    	//result = (ResultsTable) table.clone();
			    	allRois = cellROIArray;
			    	//rm.setVisible(false);
			    	rm.reset();
					for (int i=0; i<allRois.length; i++) {
						rm.add(imp, allRois[i], -1);
					}
					rm.runCommand("Show All");
					refreshSpotRoi(allRois);
					updateLinkInfoToSpotRois();
					//rm.setVisible(true);	
			    }
			});
			
			secondButtonPanel.add(setSource);
			secondButtonPanel.add(setTarget);
			secondButtonPanel.add(autoCorrect);
			correctPanel.add(secondButtonPanel);
			secondButtonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			
			// 3rd button group: remove link, exchange target
			JPanel thirdButtonPanel = new JPanel();
			thirdButtonPanel.setBorder(border);
			thirdButtonPanel.setBackground(panelColor);
			thirdButtonPanel.setLayout(new BoxLayout(thirdButtonPanel, BoxLayout.X_AXIS));
			// button to remove current link in linkList, reconstruct lineage info of deleted target
			JButton removeLink = new JButton("break link");
			removeLink.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	// get source and target ROI name, and index(s) in all Rois
			    	if (sourceSpot==null || targetSpot==null) return;
			    	
			    	String linkString = (String) linkList.getSelectedItem();
					spotRoi source = getSourceSpotFromLinkString(linkString);
					spotRoi target = getTargetSpotFromLinkString(linkString);
					if (sourceSpot==source && targetSpot==target) linkList.setSelectedIndex(0);
					
			    	// store track, track order, lineage, linkList
			    	storeTrackSelectionParam();
			    	//spotRoi sourceSpot = getSourceSpotFromLinkString ((String) linkList.getSelectedItem());
			    	//spotRoi targetSpot = getTargetSpotFromLinkString ((String) linkList.getSelectedItem());
			    	int sourceIndex = getROIIndex (allRois, sourceSpot.getRoiName());
			    	int targetIndex = getROIIndex (allRois, targetSpot.getRoiName());
			    	//System.out.println("sourceSpot:" +  sourceSpot.getRoiName() + "; oldTargetSpot:" + oldTargetSpot.getRoiName());
			    	// check if link exist between source and target (double check data table)
			    	int linkState = checkLink (allRois, sourceIndex, targetIndex);
			    	if (linkState<1) return;
			    	// remove link between source and target
			    	allRois = removeLink (allRois, sourceIndex, targetIndex, true, true);
			    	// sort all ROIs array and table together
			    	allRois = sortROIandTable(allRois);
			    	// update ROI Manager
			    	rm.reset();
					for (int i=0; i<allRois.length; i++) {
						rm.add(imp, allRois[i], -1);
					}
					rm.runCommand("Show All");
					// update spotRoi object
					refreshSpotRoi(allRois);
			    	// update link cost to spotRoi
					updateLinkInfoToSpotRois();
					// restore track, track order, lineage, linkList
					updateTrackSelectionParam(spotRois);
			    }
			});
			// button to change the selected target (selected in RM) to source (from linkList);
			JButton changeTarget = new JButton("change target");
			changeTarget.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	//if (trackList.getSelectedIndex()==0) return;
			    	//sourceSpot = getSourceSpotFromLinkString ((String) linkList.getSelectedItem());
			    	//spotRoi oldTargetSpot = getTargetSpotFromLinkString ((String) linkList.getSelectedItem());
			    	if (sourceSpot==null || targetSpot==null) return;
			    	
			    	// store track, track order, lineage, linkList
			    	storeTrackSelectionParam();
			    	
			    	int sourceIndex = getROIIndex (allRois, sourceSpot.getRoiName());
			    	int newTargetIndex = getROIIndex (allRois, targetSpot.getRoiName());
			    	int oldTargetIndex = -1;
			    	
			    	spotRoi oldTargetSpot = null;
			    	Object oriTrack = trackList.getSelectedItem();
			    	
			    	ArrayList<spotRoi> targets = getTargetSpotsFromSource (sourceSpot);
			    	if (targets!=null && targets.size()!=0) {
				    	if (targets.size()>1) {
				    		String[] targetName = new String[targets.size()];
				    		for (int i=0; i<targets.size(); i++) { targetName[i] = targets.get(i).getRoiName(); }
				    		GenericDialog gd = new GenericDialog("Choose which spot to replace");
				    		gd.addChoice("spot ROI Name", targetName, targetName[0]);
				    		gd.showDialog(); 
				    		if (gd.wasCanceled()) return;
				    		oldTargetSpot = targets.get(gd.getNextChoiceIndex());
				    	} else { 
				    		oldTargetSpot = targets.get(0);
				    	}
			    	}
			    	
			    	allRois = createLink (allRois, sourceIndex, newTargetIndex, true, true);
			    	allRois = sortROIandTable(allRois);
			    	
			    	rm.reset();
					for (int i=0; i<allRois.length; i++) {
						rm.add(imp, allRois[i], -1);
					}
					rm.runCommand("Show All");
					refreshSpotRoi(allRois);
					updateLinkInfoToSpotRois();
					// restore track, track order, lineage, linkList
					updateTrackSelectionParam(spotRois);
			    }
			});
			JButton addTarget = new JButton("make link");
			addTarget.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
			    	if (sourceSpot==null || targetSpot==null) return;
			    	// store track, track order, lineage, linkList
			    	storeTrackSelectionParam();
			    	
			    	int sourceIndex = getROIIndex (allRois, sourceSpot.getRoiName());
			    	int newTargetIndex = getROIIndex (allRois, targetSpot.getRoiName());
			    	int linkState = checkLink (allRois, sourceIndex, newTargetIndex);
			    	if (linkState>1) return;
			    	
			    	ArrayList<Integer> oldTargetList = getTarget(allRois, sourceIndex);
			    	boolean removeExist = true;
			    	if (oldTargetList!=null && oldTargetList.size()!=0) {
				    	if (oldTargetList.size()>1) { 
				    		IJ.error("target number error!", "More than 1 target already exists!");
				    		return;
				    	} else {
				    		String title = "Existing target: " + allRois[oldTargetList.get(0)].getName();
				    		String msg = "Add additional target besides exist target?";
				    		String yesLabel = "add"; String noLabel = "replace";
				    		YesNoCancelDialog gd = new YesNoCancelDialog(
				    				pf, title, msg, yesLabel, noLabel);
				    		if (gd.cancelPressed()) return;
				    		if (gd.yesPressed()) removeExist = false;
				    	}
			    	}
			    	allRois = createLink (allRois, sourceIndex, newTargetIndex, removeExist, true);
			    	allRois = sortROIandTable(allRois);
			    	rm.reset();
					for (int i=0; i<allRois.length; i++) {
						rm.add(imp, allRois[i], -1);
					}
					rm.runCommand("Show All");
					refreshSpotRoi(allRois);
					updateLinkInfoToSpotRois();
					// restore track, track order, lineage, linkList
					updateTrackSelectionParam(spotRois);
			    }
			});
			
			thirdButtonPanel.add(removeLink);
			thirdButtonPanel.add(changeTarget);
			thirdButtonPanel.add(addTarget);
			correctPanel.add(thirdButtonPanel);
			thirdButtonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
						
			
			correctPanel.setPreferredSize(panelContentSize);
			correctPanel.setMaximumSize(panelMax);
			correctPanel.setMinimumSize(panelMin);
			
			parentPanel.add(correctPanel);
			correctPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			parentPanel.setPreferredSize(panelParentSize);
			
			f.add(parentPanel);
			f.pack();
		}
		
		
		public void addCellPanelToFrame(PlugInFrame f) {
			/*
			 * construct the cell selection panel
			 */
			JPanel parentPanel = new JPanel();
			parentPanel.setBorder(border);
			parentPanel.setBackground(f.getBackground());
			parentPanel.setLayout(new BoxLayout(parentPanel, BoxLayout.Y_AXIS));
			
			JPanel cellSelection = new JPanel();
			cellSelection.setBorder(border);
			cellSelection.setBackground(panelColor);
			cellSelection.setLayout(new BoxLayout(cellSelection, BoxLayout.Y_AXIS));
			//cellSelection.setMaximumSize(new Dimension(400, 200));

			int cellIdx = 0;
			trackedCells = new ArrayList<ArrayList<Roi>>();
			ArrayList<Roi> trackedCell = new ArrayList<Roi>();
			trackedCells.add(trackedCell);
			String[] cellNames = new String[1];
			cellNames[0] = "cell 1";
			JComboBox cellList = new JComboBox(cellNames);
			cellList.setSelectedIndex(cellIdx);
			cellList.setMaximumSize(new Dimension(150, 30));
			
			cellSelection.add(cellList);
			cellList.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			JPanel buttonPanel = new JPanel();
			JButton newCell = new JButton("new");
			newCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
					//ArrayList<Roi> newC = new ArrayList<Roi>();
					int nCells = trackedCells.size();
					String[] names = new String[nCells+1];
					for (int i=0; i<nCells; i++) {
						names[i] = (String) cellList.getItemAt(i);
					}
					trackedCells.add(new ArrayList<Roi>());
					names[nCells] = "cell " + String.valueOf(nCells+1);
					//cellNames = names;
					DefaultComboBoxModel model = new DefaultComboBoxModel(names);
			        cellList.setModel(model);
			        cellList.setSelectedIndex(trackedCells.size()-1);
			    }
			});
			JButton addToCell = new JButton("add to");
			addToCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	int cellIdx = cellList.getSelectedIndex();
			    	Roi[] selectedRois = rm.getSelectedRoisAsArray();
			    	for (int i=0; i<selectedRois.length; i++) {
			    		trackedCells.get(cellIdx).add(selectedRois[i]);
			    		//trackedCells.get(cellIdx).add(rm.getRoi(i));
			    	}
			    }
			});
			JButton updateCell = new JButton("update");
			updateCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	int cellIdx = cellList.getSelectedIndex();
			    	trackedCells.get(cellIdx).clear();
			    	for (int i=0; i<rm.getCount(); i++) {
			    		trackedCells.get(cellIdx).add(rm.getRoi(i));
			    	}
			    }
			});
			JButton showCell = new JButton("show");
			showCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) { 
					int cellIdx = cellList.getSelectedIndex();
					ArrayList<Roi> selectedCell = trackedCells.get(cellIdx);
					if (selectedCell.size()==0) return;
			    	rm.setVisible(false);
			    	rm.reset();
					for (int i=0; i<selectedCell.size(); i++) {
						rm.add(imp, selectedCell.get(i), -1);
					}
					rm.setVisible(true);
			    }
			});
			JButton saveCell = new JButton("save");
			saveCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	int cellIdx = cellList.getSelectedIndex();
			    	String cellName = (String) cellList.getSelectedItem();
					ArrayList<Roi> selectedCell = trackedCells.get(cellIdx);
					if (selectedCell.size()==0) return;
			    	rm.setVisible(false);
			    	rm.reset();
					for (int i=0; i<selectedCell.size(); i++) {
						rm.add(imp, selectedCell.get(i), -1);
					}
					rm.setVisible(true);
					String impTitle = "";
					if (imp!=null) impTitle = imp.getTitle();
					SaveDialog sd = new SaveDialog("Save cell ROIs...", cellName, ".zip");
					String name = sd.getFileName();
					if (name == null)
						return;
					if (!(name.endsWith(".zip") || name.endsWith(".ZIP")))
						name = name + ".zip";
					String dir = sd.getDirectory();
					String path = dir+name;
					rm.deselect();
					rm.runCommand("Save", path);
			    }
			});
			JButton loadCell = new JButton("load");
			loadCell.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	
			    	OpenDialog od = new OpenDialog("Load cell ROIs...");
			    	String path = od.getPath();
					if (path == null || !path.toLowerCase().endsWith(".zip"))
						return;
					String cellName = od.getFileName();
					if (cellName.toLowerCase().endsWith(".zip"))
						cellName = cellName.substring(0, cellName.length()-4);
					
					int nCells = trackedCells.size();
					String[] names = new String[nCells+1];
					for (int i=0; i<nCells; i++) {
						names[i] = (String) cellList.getItemAt(i);
					}

			    	Roi[] rmRois = rm.getRoisAsArray();
			    	rm.setVisible(false);
			    	rm.reset();
			    	rm.runCommand("Open", path);
			    	Roi[] cellRois = rm.getRoisAsArray();
			    	rm.reset();
			    	for (int i=0; i<rmRois.length; i++) {
						rm.add(imp, rmRois[i], -1);
					}
					rm.setVisible(true);
					
			    	if (cellRois==null || cellRois.length==0) return;
			    	ArrayList<Roi> loadedCell = new ArrayList<Roi>(Arrays.asList(cellRois));
			    	
			    	names[nCells] = cellName;
			    	trackedCells.add(loadedCell);
					
					DefaultComboBoxModel model = new DefaultComboBoxModel(names);
			        cellList.setModel(model);
			        cellList.setSelectedIndex(trackedCells.size()-1);
			    }
			});
			JButton createMovie = new JButton("movie");
			createMovie.addActionListener(new ActionListener() { 
			    @Override 
			    public void actionPerformed(ActionEvent ae) {
			    	if (imp==null || rm==null || rm.getCount()==0) return;
			    	int cellIdx = cellList.getSelectedIndex();
			    	String cellName = (String) cellList.getSelectedItem();
			    	
			    	DefaultPrefService prefs = new DefaultPrefService();
			    	//cropSilent = prefs.getBoolean(Boolean.class, "FucciTM-cropSilent", cropSilent);
					cropSize = prefs.getInt(Integer.class, "FucciTM-cropSize", cropSize);
					GenericDialog gd = new GenericDialog("Crop Cell Movie");
					//gd.addCheckbox("silent mode", cropSilent);
					gd.addNumericField("crop size", cropSize, 0, 3, "pixel");
					gd.showDialog();
					if (gd.wasCanceled()) return;
					//cropSilent = gd.getNextBoolean();
					cropSize = (int) gd.getNextNumber();
					//prefs.put(Boolean.class, "FucciTM-cropSilent", cropSilent);
					prefs.put(Integer.class, "FucciTM-cropSize", cropSize);
					
					int width = imp.getWidth(); int height = imp.getHeight();
					int nROI = rm.getCount();
					
					//ImageStack cellStack = new ImageStack(cropSize, cropSize, nROI);
					ImagePlus[] cellCrops = new ImagePlus[nROI];
					for (int i=0; i<nROI; i++) {
						Roi r = rm.getRoi(i);
		 				double centerX = r.getXBase() + r.getFloatWidth()/2;
		 				double centerY = r.getYBase() + r.getFloatHeight()/2;
		 				
		 				int xOff = Math.max((int)(cropSize/2-centerX), 0);
						int yOff = Math.max((int)(cropSize/2-centerY), 0);
						Roi newRoi = new Roi((int)(centerX-cropSize/2), (int)(centerY-cropSize/2), cropSize, cropSize);
						
						//if (!cropSilent)
						//	rm.select(imp, i);
						imp.setRoi(newRoi, false);
						
						cellCrops[i] = new Duplicator().run(imp, 1, imp.getNChannels(), 1, imp.getNSlices(), r.getTPosition(), r.getTPosition());
						cellCrops[i].setStack(new CanvasResizer().expandStack(cellCrops[i].getStack(), cropSize, cropSize, xOff, yOff));

					}
					imp.deleteRoi();
					ImagePlus cellMovie = Concatenator.run(cellCrops);
					cellMovie.setTitle(cellName);
					
					int nChannels = imp.getNChannels(); int currentChannel = imp.getC();
					boolean[] x = ((CompositeImage)imp).getActiveChannels();
					String activeChannels = "";
					for (int c=0; c<nChannels; c++) {
						imp.setPositionWithoutUpdate(c+1, imp.getZ(), imp.getT());
						cellMovie.setDisplayRange(imp.getDisplayRangeMin(), imp.getDisplayRangeMax(), c+1);
						activeChannels += (x[c]?"1":"0");
					}
					imp.setPositionWithoutUpdate(currentChannel, imp.getZ(), imp.getT());
					cellMovie.setActiveChannels(activeChannels);
					cellMovie.show();
					System.gc();
			    }
			});
			
			JButton createPlot = new JButton("plot");
			createPlot.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent ae) {
					GeneratePlot.plotLineageWithImgAndRoiManager(imp);
				}
			});
			
			JButton rebuildLineage = new JButton("rebuild");
			rebuildLineage.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent ae) {
					// rebuild lineage with cell
					String cellName = (String) cellList.getSelectedItem();
					rebuildLineage(cellName, cellList.getSelectedIndex(), imp);
				}
			});
			
			JButton showTable = new JButton("table");
			showTable.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent ae) {
					GeneratePlot.generateResultTable (imp, (String) cellList.getSelectedItem());
				}
			});
			
			JButton modifyLineage = new JButton("modify");
			modifyLineage.addActionListener(new ActionListener() {
				@Override
				public void actionPerformed(ActionEvent ae) {
					if (imp==null || rm==null || rm.getCount()==0)	return;
					final String[] action = new String[] {"create","remove"};
					Roi[] rois = rm.getRoisAsArray();
					int selectedIndex = rm.getSelectedIndex();
					if (selectedIndex==-1) selectedIndex = 0;
					String[] roiName = new String[rois.length];
					for (int i=0; i<rois.length; i++) {
						roiName[i] = rois[i].getName();
					}
					GenericDialog gd = new GenericDialog("Modify Cell Lineage");
					gd.addChoice("link", action, action[1]);
					gd.addChoice("source", roiName, roiName[selectedIndex]);
					gd.addChoice("target", roiName, roiName[selectedIndex]);
					gd.showDialog();
					if (gd.wasCanceled()) return;
					boolean createLink = (gd.getNextChoiceIndex()==0);
					int sourceIndex = gd.getNextChoiceIndex();
					int targetIndex = gd.getNextChoiceIndex();
					
					if (createLink) {
						rois = createLink (rois, sourceIndex, targetIndex, false, false);
					} else {
						rois = removeLink (rois, sourceIndex, targetIndex, true, false);
					}
					rois = sortROI(rois);
					rm.reset();
					for (int i=0; i<rois.length; i++) {
						rm.add(imp, rois[i], -1);
					}
					rm.runCommand("Show All");
				
				}
			});
			
			GroupLayout buttonLayout = new GroupLayout(buttonPanel);
			buttonPanel.setLayout(buttonLayout);
			buttonLayout.setAutoCreateGaps(true);
			buttonLayout.setAutoCreateContainerGaps(true);

			buttonLayout.setHorizontalGroup(buttonLayout.createParallelGroup(GroupLayout.Alignment.LEADING)
				 .addGroup(buttonLayout.createSequentialGroup()
				    .addComponent(newCell)
				    .addComponent(addToCell)
				    .addComponent(updateCell))
				 .addGroup(buttonLayout.createSequentialGroup()
				    .addComponent(showCell)
				    .addComponent(saveCell)
				    .addComponent(loadCell))
				 .addGroup(buttonLayout.createSequentialGroup()
					.addComponent(createMovie)
					.addComponent(createPlot)
					.addComponent(rebuildLineage))
				 .addGroup(buttonLayout.createSequentialGroup()
					.addComponent(showTable)
					.addComponent(modifyLineage)));
				 	
			buttonLayout.linkSize(SwingConstants.HORIZONTAL, 
					newCell, showCell, addToCell, 
					updateCell, saveCell, loadCell, 
					createMovie, createPlot, rebuildLineage, 
					showTable, modifyLineage);	

			buttonLayout.setVerticalGroup(buttonLayout.createSequentialGroup()
				.addGroup(buttonLayout.createParallelGroup(GroupLayout.Alignment.BASELINE)
					.addGroup(buttonLayout.createSequentialGroup()
			                .addComponent(newCell)
			                .addComponent(showCell)
			                .addComponent(createMovie)
			                .addComponent(showTable))
					.addGroup(buttonLayout.createSequentialGroup()
			                .addComponent(addToCell)
			                .addComponent(saveCell)
			                .addComponent(createPlot)
			                .addComponent(modifyLineage))
					.addGroup(buttonLayout.createSequentialGroup()
			                .addComponent(updateCell)
			                .addComponent(loadCell)
			                .addComponent(rebuildLineage))));
			
			buttonPanel.setBorder(border);
			buttonPanel.setBackground(panelColor);
			//buttonPanel.setMaximumSize(panelMax);
			
			cellSelection.add(buttonPanel);
			buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
			
			cellSelection.setPreferredSize(panelContentSize);
			cellSelection.setMaximumSize(panelMax);
			cellSelection.setMinimumSize(panelMin);
			
			parentPanel.add(cellSelection);
			cellSelection.setAlignmentX(Component.CENTER_ALIGNMENT);
			parentPanel.setPreferredSize(panelParentSize);
			
			f.add(parentPanel);
			f.pack();
		}
		
		
		private void storeTrackSelectionParam () {
			selectedTrack = (int) trackList.getSelectedItem();
			selectedLineage = (String) lineageList.getSelectedItem();
		}
		private void updateTrackSelectionParam(ArrayList<spotRoi> spotRois) {
			int sortOption = sortTracks.getSelectedIndex();
			//ArrayList<Integer> sortedTracks = getSortedTrackIDs(spotRois, 0);
    		trackIDArray_ID = null;			trackIDArray_length = null;			trackIDArray_start = null;
    		trackIDArray_lineage = null;	trackIDArray_ROI = null;			trackIDArray_mitosis = null;
    		ArrayList<Integer> sortedTracks = getSortedTrackIDs(spotRois, sortOption);
    		Integer[] trackIDArray_temp = sortedTracks.toArray(new Integer[sortedTracks.size()]);
    		DefaultComboBoxModel model = new DefaultComboBoxModel(trackIDArray_temp);
			trackRois = getTrackRois(spotRois, selectedTrack);
			lineage = getLineages(trackRois);
			lineageArray = lineage.toArray(new String[lineage.size()]);
			trackList.setModel(model);
			trackList.setSelectedItem(selectedTrack);
			lineageList.setModel(new DefaultComboBoxModel(lineageArray));
			int selectedLineageIndex = 0;
			for (int i=0; i<lineageArray.length; i++) {
				if (lineageArray[i].contentEquals(selectedLineage)) { selectedLineageIndex = i; break; }
			}
			lineageList.setSelectedIndex(selectedLineageIndex);
		}
		
		public void rebuildLineage(String cellName, int cellIdx, ImagePlus imp) {
			ArrayList<Roi> selectedCell = trackedCells.get(cellIdx);
			if (selectedCell.size()==0) return;
			Roi[] cellRois = new Roi[selectedCell.size()];
			for (int i=0; i<selectedCell.size(); i++) {
				cellRois[i] = selectedCell.get(i);
			}
			TrackMate trackmate = create_trackmate(cellRois, imp);
			process(trackmate);
			
			Model model = trackmate.getModel();
			TrackModel tm = model.getTrackModel();
			Set<Integer> trackIDs = tm.trackIDs(true);
			int nZeros = String.valueOf(model.getSpots().getNSpots(true)).length();
			TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();
			double pixelSize = imp.getCalibration().pixelWidth;
			int roiCount = 0;
					
			ArrayList<Spot> startSpots = new ArrayList<Spot>(); // store starting spot for each track
			for (final Integer trackID : trackIDs) {
				Set<Spot> spots = tm.trackSpots(trackID);
				for (Spot spot : spots) {
					int nSource = neighborIndex.predecessorsOf(spot).size();
					if (nSource==0) {
						startSpots.add(spot);
					}
				}
			}
			if (startSpots.size()>0) rm.reset();

			// we iterate through all starting spot (spot has no predecessor)
			for (Spot startSpot : startSpots) {
				int trackID = tm.trackIDOf(startSpot);
				// 3 variables for temporary store track segments to form the single tracks
				ArrayList<ArrayList<Integer>> segments = new ArrayList<ArrayList<Integer>>();
				ArrayList<Integer> seg = new ArrayList<Integer>();
				String lineageID = String.valueOf(trackID) + "-";
				
				GraphIterator<Spot, DefaultWeightedEdge> iter = tm.getDepthFirstIterator(startSpot, true);
				
				while (iter.hasNext()) {
					
					Spot currentSpot = iter.next();
					String roiName = "ROI:" + String.format("%0"+String.valueOf(nZeros)+"d", ++roiCount) + ", ID:" + lineageID;
					double x = currentSpot.getFeature("POSITION_X");
					double y = currentSpot.getFeature("POSITION_Y");
					double t = currentSpot.getFeature("FRAME") + 1;
					double d = 10;
					//Roi spotRoi = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
					//Roi spotRoi = new PointRoi((x)/pixelSize,(y)/pixelSize);
					int idx = getRoiIndex (cellRois, x/pixelSize, y/pixelSize, t);
					Roi spotRoi = (idx==-1) ? new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize) : cellRois[idx];
					spotRoi.setPosition(0, 1, (int)t);
					// use the circle ROI to crop around original image and do mitosis check
					imp.setPositionWithoutUpdate(spotRoi.getCPosition(), spotRoi.getZPosition(), spotRoi.getTPosition());
					imp.setRoi(spotRoi, false);
					// crop image around spot, process updated spot Roi to the cropped image

					rm.add(imp, spotRoi, currentSpot.ID());	// add roi with imp to RoiManager
					rm.rename(rm.getCount()-1, roiName);
					seg.add(rm.getCount()-1);
					
					// check segment condition
					//int endID = -1;
					List<Spot> predecessor = neighborIndex.predecessorListOf(currentSpot);
					//if (predecessor.size() == 1) endID = predecessor.get(0).ID();

					int nTarget = neighborIndex.successorsOf(currentSpot).size();
					switch(nTarget) {
						case 2: // cell has two daughter cells
							lineageID += "0";
							segments.add(seg);
							seg = new ArrayList<Integer>();
							break;
						case 0: // current cell ends
							// get stored segments into a single track, and then add to cell list
							ArrayList<Integer> singleTracks = new ArrayList<Integer>();
							for (ArrayList<Integer> segment : segments) {
								singleTracks.addAll(segment);
							}
							singleTracks.addAll(seg);
							//singleCells.put(lineageID, singleTracks);
							seg = new ArrayList<Integer>();
							// clear current seg buffer, remove last part of the segments
							if (lineageID.endsWith("0")) {
								lineageID = lineageID.substring(0, lineageID.length()-1) + "1";
							} else if (lineageID.endsWith("1")) {
								//lineageID = lineageID.substring(0, lineageID.length()-1);
								while(lineageID.endsWith("1")) {
									lineageID = lineageID.substring(0, lineageID.length()-1);
									segments.remove(segments.size()-1);
								}
								lineageID = lineageID.substring(0, lineageID.length()-1);
								lineageID += "1";
							} else if (lineageID.endsWith("-")) { // do nothing for single cell track	
							} else System.out.println("Error: " + roiName);
							break;
						case 1:	// do nothing
							break;
						default: // something is wrong
							System.out.println("Error: " + roiName);
					}
					imp.deleteRoi();	
				}
			}
			display_results_in_GUI(trackmate, imp);
		}
		
		private SpotCollection spots_from_RoiManager(Roi[] cellRois, ImagePlus imp) {
			if (imp==null || cellRois==null || cellRois.length==0) return null;
			int nSpots = cellRois.length;
			double pixelSize = imp.getCalibration().pixelWidth;
			double pixelArea = pixelSize * pixelSize;
			double frameInterval = imp.getCalibration().frameInterval;
			SpotCollection spots = new SpotCollection();
		 
		    for (int i=0; i<nSpots; i++) {
		    	Roi roi = cellRois[i];
		    	imp.setRoi(roi, false);
		    	//double x = imp.getAllStatistics().xCentroid;
		        //double y = imp.getAllStatistics().yCentroid;
		    	boolean isPoint = roi.getType()==Roi.POINT;
		    	double[] center = roi.getContourCentroid();
		    	double x = isPoint ? roi.getXBase() : center[0];
		    	double y = isPoint ? roi.getYBase() : center[1];
		    	x *= pixelSize; y *= pixelSize;
		    	int frame = roi.getTPosition();
		    	double area = roi.getStatistics().area * pixelArea;
		    	double t = (frame - 1) * frameInterval;
				double radius = isPoint ? 0.5 : Math.sqrt( area / Math.PI);
				double quality = (double)i;
		        Spot spot = new Spot(x, y, 0, radius, quality);
		        spot.putFeature( "POSITION_T", t);
		        spots.add( spot, (int)(frame-1));
		    }
		    return spots;
		}
		
		private boolean process (TrackMate trackmate) {
			//trackmate = create_trackmate(newSpotRois.toArray(), imp);
	 		boolean ok = trackmate.checkInput();
	 	    // Initial filtering
	 		ok = ok && trackmate.execInitialSpotFiltering();
	 	    // Compute spot features.
	 	    ok = ok && trackmate.computeSpotFeatures( true );
	 	    // Filter spots.
	 	    ok = ok && trackmate.execSpotFiltering( true );
	 	    // Track spots.
	 	    ok = ok && trackmate.execTracking();
	 	    // Compute track features.
	 	    ok = ok && trackmate.computeTrackFeatures( true );
	 	    // Filter tracks.
	 	    ok = ok && trackmate.execTrackFiltering( true );
	 	    // Compute edge features.
	 	    ok = ok && trackmate.computeEdgeFeatures( true );
	 	    //ok = ok && trackmate.process();
		 	
			return ok;
		}
		
		private TrackMate create_trackmate(Roi[] cellRois, ImagePlus imp) {  
		    Calibration cal = imp.getCalibration();
		    // Model.
		    Model model = new Model();
		    // model.setLogger( Logger.IJ_LOGGER );
		    //model.setLogger( Logger.DEFAULT_LOGGER);
		    model.setPhysicalUnits( cal.getUnit(), cal.getTimeUnit() );
		    // Settings.
		    Settings settings = new Settings();
		    settings.setFrom( imp );
		    // Create the TrackMate instance.
		    //settings.addSpotAnalyzerFactory(new SpotMultiChannelIntensityAnalyzerFactory());
		    TrackMate trackmate = new TrackMate( model, settings );
		    SpotAnalyzerProvider spotAnalyzerProvider = new SpotAnalyzerProvider();
			for (String key : spotAnalyzerProvider.getKeys()) {
				settings.addSpotAnalyzerFactory( spotAnalyzerProvider.getFactory( key ) );
			}
			EdgeAnalyzerProvider edgeAnalyzerProvider = new EdgeAnalyzerProvider();
			for (String key : edgeAnalyzerProvider.getKeys()) {
				settings.addEdgeAnalyzer( edgeAnalyzerProvider.getFactory( key ) );
			}
			TrackAnalyzerProvider trackAnalyzerProvider = new TrackAnalyzerProvider();
			for (String key : trackAnalyzerProvider.getKeys()) {
				settings.addTrackAnalyzer( trackAnalyzerProvider.getFactory( key ) );
			}
		    trackmate.getModel().getLogger().log( settings.toStringFeatureAnalyzersInfo() );
		    trackmate.computeSpotFeatures( true );
		    trackmate.computeEdgeFeatures( true );
		    trackmate.computeTrackFeatures( true );
		     
		    // Skip detection && get spots from results table.
		    //Roi[] spotRois = rm.getSelectedRoisAsArray();
		    SpotCollection spots = spots_from_RoiManager(cellRois, imp);
		    model.setSpots( spots, false );
		    // Configure detector. We put nothing here, since we already have the spots 
		    // from previous step.
		    settings.detectorFactory = new ManualDetectorFactory();
		    settings.detectorSettings = new HashMap<String, Object>();
		    settings.detectorSettings.put( "RADIUS" , 3.0d);
		    // Configure tracker
		    settings.trackerFactory = new SparseLAPTrackerFactory();
		    settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap();
		    settings.trackerSettings.put( "LINKING_MAX_DISTANCE" , 100.0d);
		    settings.trackerSettings.put( "GAP_CLOSING_MAX_DISTANCE" , 100.0d);
		    settings.trackerSettings.put( "MAX_FRAME_GAP" , 4);
		    settings.trackerSettings.put( "ALLOW_TRACK_SPLITTING" ,  true);
			settings.trackerSettings.put( "ALLOW_TRACK_MERGING" ,  false);
		    settings.initialSpotFilterValue = -1.0;
		    return trackmate;
		}
		/*
		private int getRoiIndex (double x, double y, double t, Roi[] cellRois) {// x, y in pixel unit
			if (cellRois==null || cellRois.length==0) return -1;
			for (int i=0; i<cellRois.length; i++) {
				if (cellRois[i].getTPosition()!=t) continue;
				//println(rois[i].getName());
				if (cellRois[i].containsPoint(x, y)) return i;
			}
			return -1;
		}
		*/
		
		private void display_results_in_GUI( TrackMate trackmate, ImagePlus imp ) {
		     
		    TrackMateGUIController gui = new TrackMateGUIController( trackmate );
		 
		    // Link displayer and GUI.
		     
		    Model model = trackmate.getModel();
		    SelectionModel selectionModel = new SelectionModel( model);
		    HyperStackDisplayer displayer = new HyperStackDisplayer( model, selectionModel, imp );
		    gui.getGuimodel().addView( displayer );
		    Map<String, Object> displaySettings = new HashMap<>();
		    displaySettings = gui.getGuimodel().getDisplaySettings();
		    
		    for (String key : displaySettings.keySet()) {
		        displayer.setDisplaySettings( key, displaySettings.get( key ) );
		    }
		    displayer.render();
		    displayer.refresh();
		     
		    gui.setGUIStateString( "ConfigureViews" );
		}
		
		public int getRoiIndex (Roi[] cellRois, double x, double y, double t) {// x, y in pixel unit
			if (cellRois==null || cellRois.length==0) return -1;
			for (int i=0; i<cellRois.length; i++) {
				if (cellRois[i].getTPosition()!=t) continue;
				if (cellRois[i].contains((int)x, (int)y)) return i;
			}
			return -1;
		}

		
		@Override
		public void run(String arg) {
			imp = WindowManager.getCurrentImage();
			rm = RoiManager.getInstance();
			//if (imp==null || rm==null) return;
			
			pf = new PlugInFrame("Track Manager");
			pf.setLayout(new BoxLayout(pf, BoxLayout.Y_AXIS));
			
			addTrackPanelToFrame(pf);
			addCorrectionPanelToFrame(pf);
			addCellPanelToFrame(pf);

			WindowManager.addWindow(pf);
			pf.pack();
			pf.setSize(380, 700);
			pf.setMaximumSize(new Dimension(400, 900));
			pf.setMinimumSize(new Dimension(380, 400));
			pf.setVisible(true);
			pf.setLocationRelativeTo(null);
			GUI.center(pf);
		}
		
		
		public void main(String[] args) {
			TrackManager tm = new TrackManager();
			tm.run(null);
		}
		
		
		
		/*
		 * 
		 * auto correct section
		 */
		
		private boolean autoCorrectDialog () {
			if (table == null || tablePath==null || tablePath=="") return false;
			// make use of scijava parameter persistence storage	
			DefaultPrefService prefs = new DefaultPrefService();
			minCost = prefs.getDouble(Double.class, "Fucci-autoCorrect-minCost", minCost);
			maxCount = prefs.getDouble(Double.class, "Fucci-autoCorrect-maxCount", maxCount);
			rangeXY = prefs.getDouble(Double.class, "Fucci-autoCorrect-rangeXY", rangeXY);
			rangeT = prefs.getDouble(Double.class, "Fucci-autoCorrect-rangeT", rangeT);
			minSimScore = prefs.getDouble(Double.class, "Fucci-autoCorrect-minSimScore", minSimScore);
			// user dialog
			GenericDialogPlus gd = new GenericDialogPlus("auto correction parameters");
			// cell similarity parameters
			gd.addNumericField("cost threshold", minCost, 0);
			gd.addNumericField("max correction count", maxCount, 0);
			gd.addNumericField("search radius (XY)", rangeXY, 0, 4, "pixel"); // "μm"
			gd.addNumericField("search radius (T)", rangeT, 0, 4, "frame");
			gd.addNumericField("min similarity score", minSimScore, 1);
			gd.showDialog();
			if (gd.wasCanceled())	return false;
			minCost = gd.getNextNumber();
			maxCount = gd.getNextNumber();
			rangeXY =  gd.getNextNumber();
			rangeT =  gd.getNextNumber();
			minSimScore =  gd.getNextNumber();
			// save to persistence storage
			prefs.put(Double.class, "Fucci-autoCorrect-minCost", minCost);
			prefs.put(Double.class, "Fucci-autoCorrect-maxCount", maxCount);
			prefs.put(Double.class, "Fucci-autoCorrect-rangeXY", rangeXY);
			prefs.put(Double.class, "Fucci-autoCorrect-rangeT", rangeT);
			prefs.put(Double.class, "Fucci-autoCorrect-minSimScore", minSimScore);
			return true;
		}
		
		private HashMap<Integer, ArrayList<Integer>> makeFrameROIs (Roi[] cellROIArray) { // ROIs should have already T position!
			//double start = System.currentTimeMillis();
			int nROI = cellROIArray.length;
			//HashMap<Integer, ArrayList<Roi>> cellRois = new HashMap<Integer, ArrayList<Roi>>();
			//int tPrev = 1; int fStart = 0; int fEnd = 0;
			HashMap<Integer, ArrayList<Integer>> frameROIs = new HashMap<Integer, ArrayList<Integer>>();
			for (int i=0; i<nROI; i++) {
				int tCurrent = cellROIArray[i].getTPosition();
				if (frameROIs.containsKey(tCurrent)) {
					frameROIs.get(tCurrent).add(i);
				} else {
					ArrayList<Integer> roiList = new ArrayList<Integer>();
					roiList.add(i);
					frameROIs.put(tCurrent, roiList);
				}
			}
			//double duration = ( System.currentTimeMillis() - start ) / 1000;
			//System.out.println("get ROI into hashmap takes " + duration + " seconds.");
			// debug frame ROI: roi size mismatch!!!

			return frameROIs;
		}
		
		
		// delegate 1st panel refresh button function
		private void refreshTrackManager() {
			imp = WindowManager.getCurrentImage();
	    	rm = RoiManager.getInstance();
			if (imp==null || rm==null) return;
			rm.runCommand("Show All");
			//sortROI();
			int nROI = rm.getCount();
			allRois = rm.getRoisAsArray();
			//trackedCells = new ArrayList<ArrayList<Roi>>();
	    	spotRois = new ArrayList<spotRoi>();
			for (int i=0; i<nROI; i++) {
				spotRois.add(new spotRoi(i));
			}
			trackIDs = getTrackIDs(spotRois);
			trackIDArray = trackIDs.toArray(new Integer[trackIDs.size()]);
			trackRois = getTrackRois(spotRois, selectedTrack);
			lineage = getLineages(trackRois);
			lineageArray = lineage.toArray(new String[lineage.size()]);
			trackIDArray_ID = null;			trackIDArray_length = null; 	trackIDArray_start = null;
			trackIDArray_lineage = null;	trackIDArray_ROI = null; 		trackIDArray_mitosis = null;
			//selectedTrack = 0; selectedLineage = "";
			DefaultComboBoxModel modelTrackList = new DefaultComboBoxModel(trackIDArray);
			trackList.setModel(modelTrackList);
	        DefaultComboBoxModel modelCellList = new DefaultComboBoxModel(lineageArray);
	        lineageList.setModel(modelCellList);
	        lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
		}
		// refreshSpotRoi
		private void refreshSpotRoi (Roi[] rois) {
			if (rois==null || rois.length==0) return;
			spotRois = new ArrayList<spotRoi>();
			for (int i=0; i<rois.length; i++) {
				spotRois.add(new spotRoi(i));
			}
			trackIDs = getTrackIDs(spotRois);
			trackIDArray = trackIDs.toArray(new Integer[trackIDs.size()]);
			trackRois = getTrackRois(spotRois, selectedTrack);
			lineage = getLineages(trackRois);
			lineageArray = lineage.toArray(new String[lineage.size()]);
			trackIDArray_ID = null;			trackIDArray_length = null; 	trackIDArray_start = null;
			trackIDArray_lineage = null;	trackIDArray_ROI = null; 		trackIDArray_mitosis = null;
			//selectedTrack = 0; selectedLineage = "";
			DefaultComboBoxModel modelTrackList = new DefaultComboBoxModel(trackIDArray);
			trackList.setModel(modelTrackList);
	        DefaultComboBoxModel modelCellList = new DefaultComboBoxModel(lineageArray);
	        lineageList.setModel(modelCellList);
	        lineageInfo.setText(getLineageInfo(spotRois, selectedTrack, selectedLineage));
	        
	        // TODO: update originally selected track, track order, and lineage
		}
		// sort ROI array by track, and sub-lineage, and frame
		private void sortROI () {
			RoiManager rm = RoiManager.getInstance();
			if (imp==null || rm==null || rm.getCount()<=1) return;
			Roi[] rois = rm.getRoisAsArray();
			Roi[] sortedRois = sortROI (rois);
			rm.reset();
			for (int i=0; i<sortedRois.length; i++) {
				rm.add(imp, sortedRois[i], -1);
			}
			rm.runCommand("Show All");
		}
		private Roi[] sortROI (Roi[] rois) {
			if (rois==null || rois.length<=1) return rois;
			ArrayList<Integer> newIndex = sortROI2(rois);
			if (newIndex.size() != rois.length) return rois;
			int nROI = rois.length;
			Roi[] sortedRois = new Roi[nROI];
			for (int i=0; i<nROI; i++) {
				sortedRois[i] = rois[ newIndex.get(i) ];
			}
			return sortedRois;
		}
		private Roi[] sortROIandTable (Roi[] rois) {
			if (rois==null || rois.length<=1) return rois;
			ArrayList<Integer> newIndex = sortROI2(rois);
			if (newIndex.size() != rois.length) {
				System.out.println("sort error, size mismatch!");
				return rois;
			}
			//table.show("debug sorted table2");
			table = sortTable(table, newIndex);
			//table.showRowIndexes(true);
			// sort rois by new index
			int nROI = rois.length;
			Roi[] sortedRois = new Roi[nROI];
			for (int i=0; i<nROI; i++) {
				sortedRois[i] = rois[ newIndex.get(i) ];
			}
			return sortedRois;
		}
		private ResultsTable sortTable (ResultsTable table, ArrayList<Integer> index) {
			if (table==null || table.size()<=1 || index==null || index.size()!=table.size()) return table;
			String[] headings = table.getHeadings();
			ResultsTable sortedTable = new ResultsTable(table.size());
			for (int i=0; i<table.size(); i++) {
				int idx = index.get(i);
				for (String heading : headings) {
					if ( heading.contains("Lineage") )
						sortedTable.setValue(heading, i, table.getStringValue(heading, idx));
					else
						sortedTable.setValue(heading, i, table.getValue(heading, idx));
				}
			}
			sortedTable = prepareSourceIndexColumn (sortedTable);
			return sortedTable;
		}
		private ResultsTable prepareSourceIndexColumn (ResultsTable table) {
			if (table==null || !table.columnExists("Spot ID") || !table.columnExists("Source Spot ID")) return table;
			int nRow = table.size();
			ResultsTable newTable = new ResultsTable(nRow);
			//boolean colExist = table.columnExists("Source ROI index");
			String[] headings = table.getHeadings();
			int nCol = headings.length;
			for (int j=0; j<nCol; j++) {
				if ( j==2 ) { // source ROI index column
					for (int i=0; i<nRow; i++) {
						newTable.setValue ( "Source ROI index", i, getSourceSpotIndex (table, i) );
					}
				}
				newTable.setColumn( headings[j], table.getColumnAsVariables( headings[j] ));
			}
			return newTable;
		}
		/*
		private Roi[] sortROI (Roi[] rois, ResultsTable result) {
			if (rois==null || rois.length<=1) return rois;
			result = table;
			if (table==null || table.size() != rois.length) return rois;
			ArrayList<Integer> newIndex = sortROI2(rois);
			if (newIndex.size() != rois.length) return rois;
			int nROI = rois.length;
			Roi[] sortedRois = new Roi[nROI];
			for (int i=0; i<nROI; i++) {
				sortedRois[i] = rois[ newIndex.get(i) ];
			}
			return sortedRois;
		}
		*/
		private ArrayList<Integer> sortROI2 (Roi[] rois) {
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
		// get current maximum track ID
		private int getMaxTrackID (Roi[] rois) {
			int maxID = -1;
			for (int i=0; i<rois.length; i++) {
				int ID = getTrack ( rois[i].getName() );
				if (maxID < ID) maxID = ID;
			}
			return maxID;
		}
		// get lineage ID: ID:[311-100001]
		private String getLineage (String name) {
			int idx1 = name.indexOf("ID:");
			if (idx1 == -1) return null;
			int idx2 = name.indexOf(",", idx1);
			if (idx2 == -1) idx2 = name.length();
			return name.substring(idx1+3, idx2);
		}
		// get track ID: 311
		private int getTrack (String name) {
			String lineage = getLineage(name);
			if (null == lineage || lineage.length()==0) return -1;
			int idx = lineage.indexOf("-");
			if (idx == -1) return -1;
			return Integer.valueOf(lineage.substring(0, idx));
		}
		// get sub-lineage ID: 100001
		private String getSubLineage (String name) {
			String lineage = getLineage(name);
			if (null == lineage || lineage.length()==0) return null;
			int idx = lineage.indexOf("-");
			if (idx == -1) return null;
			return lineage.substring(idx+1, lineage.length());
		}
		// get all sub-lineage from current spotROI forward/backward
		private ArrayList<Integer> getLineageForward (Roi[] rois, int index) {
			return getLineageForBackWard(rois, index, true);
		}
		private ArrayList<Integer> getLineageBackward (Roi[] rois, int index) {
			return getLineageForBackWard(rois, index, false);
		}
		private ArrayList<Integer> getLineageForBackWard (Roi[] rois, int index, boolean forward) {
			if (index == -1) return null;
			String roiName = rois[index].getName();
			int frame = rois[index].getTPosition();
			int trackID = getTrack(roiName);
			if (trackID == -1) return null;
			String lineage = getLineage(roiName);
			if (lineage == null) return null;
			lineage = "ID:" + lineage;
			
			ArrayList<Integer> subLineageList = new ArrayList<Integer>();
			
			for (int i=0; i<rois.length; i++) {
				if (getTrack(rois[i].getName()) != trackID) continue;
				
				int iFrame = rois[i].getTPosition();
				if ( forward ? iFrame < frame : iFrame > frame) continue;
					
				String iLineage = "ID:" + getLineage(rois[i].getName());
				if ( forward ? iLineage.contains(lineage) : lineage.contains(iLineage))
					subLineageList.add(i);
			}
			return subLineageList;
		}
		
		// get the start or end point of a certain sub-lineage, return its index in ROI array
		private int getLineageEndPoint (Roi[] rois, int index, boolean getStartPoint) { // lineage is ID:[4-1001]
			if (rois==null || rois.length==0) return -1;
			if (index==-1 || index>=rois.length) return -1;
			
			Roi roi = rois[index];
			//int frame = roi.getTPosition();
			String name = roi.getName();
			String lineage = "ID:" + getLineage(name);
			int track = getTrack(name);
			//String subLineage = getSubLineage(name);
			
			int endPointIndex = -1; int endPointFrame = getStartPoint ? Integer.MAX_VALUE : -1;
			for (int i=0; i<rois.length; i++) {
				String iName = rois[i].getName();
				int iTrack = getTrack(iName);
				if (iTrack != track) continue;
				String iLineage = "ID:" + getLineage(iName);
				if (!iLineage.contentEquals(lineage)) continue;
				int iFrame = rois[i].getTPosition();
				
				if (getStartPoint ? iFrame < endPointFrame : iFrame > endPointFrame) {
					endPointIndex = i;
					endPointFrame = iFrame;
				}	
			}
			return endPointIndex;
		}
		// given ROI array, target index, return source index
		private int getSource (Roi[] rois, int targetIndex) {
			if (rois==null || rois.length==0) return -1;
			if (targetIndex==-1 || targetIndex>=rois.length) return -1;
			
			Roi targetRoi = rois[targetIndex];
			int targetFrame = targetRoi.getTPosition();
			//String targetName = targetRoi.getName();
			//String targetLineage = "ID:" + getLineage(targetName);
			//int targetTrack = getTrack(targetName);
			//String targetSubLineage = getSubLineage(targetName);
			ArrayList<Integer> lineageList = getLineageBackward(rois, targetIndex);
			
			int sourceIndex = -1; int sourceFrame = -1;
			for (int i : lineageList) {
				int frame = rois[i].getTPosition();
				if (frame >= targetFrame) continue;	// skip ROI presented after target
				if (frame == targetFrame-1) { return i; }	// return the first ROI that right before target frame
				if (frame > sourceFrame) {
					sourceIndex = i;
					sourceFrame = frame;
				}
			}
			return sourceIndex;
		}
		// given ROI array, source index, return target index
		private ArrayList<Integer> getTarget (Roi[] rois, int sourceIndex) {
			if (rois==null || rois.length==0) return null;
			if (sourceIndex==-1 || sourceIndex>=rois.length) return null;
			
			Roi sourceRoi = rois[sourceIndex];
			int sourceFrame = sourceRoi.getTPosition();
			String sourceName = sourceRoi.getName();
			String sourceLineage = "ID:" + getLineage(sourceName);
			//int sourceTrack = getTrack(sourceName);
			//String sourceSubLineage = getSubLineage(sourceName);
			ArrayList<Integer> lineageList = getLineageForward(rois, sourceIndex);
			
			ArrayList<Integer> targetList = new ArrayList<Integer>();
			boolean endPoint = (getLineageEndPoint(rois, sourceIndex, false) == sourceIndex);
			if (!endPoint) {	// source is not the end of current lineage, 1 target
				int targetIndex = -1; int targetFrame = Integer.MAX_VALUE;
				for (int i : lineageList) {
					if (i == sourceIndex) continue;
					int frame = rois[i].getTPosition();
					if (frame == sourceFrame+1) { targetIndex = i; break; }	// return the first ROI that right after source frame
					if (frame < targetFrame) {	// get the first ROI presented after source
						targetIndex = i;
						targetFrame = frame;
					}
				}
				targetList.add(targetIndex); 
			} else {	// source is the end of current lineage, 0 or 2 target
				String subLineage0 = sourceLineage + "0";
				String subLineage1 = sourceLineage + "1";
				for (int i : lineageList) {
					if (targetList.size() == 2) break;
					if (i == sourceIndex) continue;
					String lineage = "ID:" + getLineage(rois[i].getName());
					if (lineage.contentEquals(subLineage0)) {
						int sub0 = getLineageEndPoint(rois, i, true);
						if (!targetList.contains(sub0)) targetList.add(sub0);
					} else if (lineage.contentEquals(subLineage1)) {
						int sub1 = getLineageEndPoint(rois, i, true);
						if (!targetList.contains(sub1)) targetList.add(sub1);
					}
				}
			}
			return targetList;
		}
		// check if link exist between source and target: -1: error; 0: no link; 1: 1to1 link; 2: 1to2 link
		private int checkLink (Roi[] rois, int sourceIndex, int targetIndex) {
			if (rois==null || rois.length==0) return -1;
			if (sourceIndex==-1 || targetIndex==-1) return -1;
			if (sourceIndex>=rois.length || targetIndex>=rois.length) return -1;
			
			ArrayList<Integer> sourceLineageList = getLineageForward(rois, sourceIndex);
			//ArrayList<Integer> targetLineageList = getAllSubLineage(rois, targetIndex);
			if (!sourceLineageList.contains(targetIndex)) return 0;
			
			Roi sourceRoi = rois[sourceIndex];			Roi targetRoi = rois[targetIndex];
			int sourceFrame = sourceRoi.getTPosition();	int targetFrame = targetRoi.getTPosition();
			if (sourceFrame >= targetFrame) return 0;	// source present not before target
		
			String sourceName = sourceRoi.getName();	String targetName = targetRoi.getName();
			String sourceLineage = "ID:" + getLineage(sourceName);
			String targetLineage = "ID:" + getLineage(targetName);
			
			boolean splitPoint = !sourceLineage.contentEquals(targetLineage);
			if (splitPoint) {	// source and target not int the same sub-lineage
				int lenDiff = targetLineage.length() - sourceLineage.length();
				if (lenDiff != 1) {
					System.out.println("Error! lineage wrong: Source:" + sourceName + ", Target:" + targetName);
					return 0;
				}
				if (!targetLineage.contains(sourceLineage)) {
					System.out.println("Error! lineage wrong: Source:" + sourceName + ", Target:" + targetName);
					return 0;
				}
				for (int idx : sourceLineageList) {
					if (idx == sourceIndex || idx == targetIndex) continue;
					int frame = rois[idx].getTPosition();
					String lineage = "ID:" + getLineage(rois[idx].getName());
					if (lineage.contentEquals(sourceLineage)) {
						if (frame >= sourceFrame) return 0;	// if there's ROI in source sub-lineage that present after source
					}
					if (lineage.contentEquals(targetLineage)) {
						if (frame <= targetFrame) return 0;	// if there's ROI in source sub-lineage that present before target
					}
				}
				return 2;	// if target is the first ROI in source sub-lineage
			} else {	// source and target in the same sub-lineage
				for (int idx : sourceLineageList) {
					if (idx == sourceIndex || idx == targetIndex) continue;
					int frame = rois[idx].getTPosition();
					if (frame < targetFrame) return 0;	// if there's ROI in source sub-lineage that present before target
					else if (frame == targetFrame) {
						System.out.println("Error! mulitple ROI in frame " + frame + " in lineage following: " + sourceName);
					}
				}
				return 1;	// if target is the first ROI in source sub-lineage
			}
		}
		
		// remove link between source and target
		private Roi[] removeLink (Roi[] rois, int sourceIndex, int targetIndex, boolean updateSource, boolean global) {
			//System.out.println("remove link reached.");
			if (sourceIndex==-1 || targetIndex==-1) return rois;
			if (sourceIndex>=rois.length || targetIndex>=rois.length) return rois;
			// get new track ID
			int newTrackID = getMaxTrackID(rois) + 1;
			// get source and target sub-lineage string
			String sourceLineage = getSubLineage(rois[sourceIndex].getName());
			String targetLineage = getSubLineage(rois[targetIndex].getName());
			// get source and target lineage list: Roi[] inclusive
			ArrayList<Integer> sourceLineageList = getLineageForward(rois, sourceIndex);		
			ArrayList<Integer> targetLineageList = getLineageForward(rois, targetIndex);
			// get link state: -1: error, 0: no link, 1: 1-1 link, 2: 1-2 link
			int linkState = checkLink (rois, sourceIndex, targetIndex);	
			if (linkState == -1) {
				System.out.println("break link error between:" + rois[sourceIndex].getName() + ", and:" + rois[targetIndex].getName());
				return rois; // error link bettwen source and target
			} else if (linkState == 0) {
				System.out.println("no link between:" + rois[sourceIndex].getName() + ", and:" + rois[targetIndex].getName());
				return rois; //	no link bettwen source and target
			} else {
				//System.out.println( "debug, removeLink: sourceName:" + sourceName + ", targetName:" + targetName);
				//System.out.println( "debug, removeLink: sourceSubLineage:" + sourceSubLineage);
				//System.out.println( "debug, removeLink: targetSubLineage:" + targetSubLineage);
				//System.out.println( "debug, removeLink: splitPoint:" + splitPoint + ", updateSource:" + updateSource);
				
				// move target lineage into new track
				for (int idx : targetLineageList) {
					String name = rois[idx].getName();
					String trackID = "" + getTrack(name);
					String newName = name.replace("ID:"+trackID, "ID:"+newTrackID);
					newName = newName.replace("-"+targetLineage, "-");
					rois[idx].setName(newName);
					if (global) table.setValue( "Lineage ID", idx, getLineage(newName));
				}
				if (global) table.setValue( "Source Spot ID", targetIndex, -1);
				if (global) table.setValue( "Source ROI index", targetIndex, -1);
				if (global) table.setValue( "link Cost", targetIndex, linkState / 2 - 3.5);
				//System.out.println( "debug, removeLink: after targetName:" + rois[targetIndex].getName());
				if (updateSource) table.setValue( "Target Spot number", sourceIndex, linkState-1 );
				// check and update source, only in the case when source is a split point
				if (updateSource && linkState==2) {
					boolean removedSubIsZero = targetLineage.endsWith("0");
					String remainSubLineage = removedSubIsZero ? sourceLineage + "1" : sourceLineage + "0";
					for (int idx : sourceLineageList) {
						if (idx == sourceIndex || targetLineageList.contains(idx)) continue;
						String name = rois[idx].getName();
						String newName = name.replace("-"+remainSubLineage, "-"+sourceLineage);
						rois[idx].setName(newName);
						if (global) table.setValue( "Lineage ID", idx, getLineage(newName));
					}	
				}
			}
			if (global) cellROIArray = rois;
			return rois;
		}
		// create a new link between source and target
		private Roi[] createLink (Roi[] rois, int sourceIndex, int targetIndex, boolean removeOld, boolean global) {
			if (sourceIndex==-1 || targetIndex==-1) return rois;
			if (sourceIndex>=rois.length || targetIndex>=rois.length) return rois;
			if (checkLink(rois, sourceIndex, targetIndex) > 0) return rois;	// link already exists
			
			int sourceSpotID = global ? (int) table.getValue( "Spot ID" , sourceIndex) : -1;
			
			// check link states of source and target
			ArrayList<Integer> oldTargetList = getTarget(rois, sourceIndex);
			//boolean noOldTarget = (oldTargetList.size()==0);
			int oldSourceIndex = getSource(rois, targetIndex);
			if (oldTargetList.size()==2) {
				IJ.error("Source already have 2 targets, cannot create more links. Remove at least 1 original target first.");
				return rois; // already 2 targets, cannot add more target
			}
			int oldTargetIndex = oldTargetList.size()==0 ? -1 : oldTargetList.get(0);
			// get source and target track ID
			int sourceTrack = getTrack(rois[sourceIndex].getName());
			//int targetTrack = getTrack(rois[targetIndex].getName());
			// get source and target sub-lineage string
			String sourceLineage = getSubLineage(rois[sourceIndex].getName());
			//String targetLineage = getSubLineage(rois[targetIndex].getName());
			// get source and target lineage list: Roi[] inclusive
			ArrayList<Integer> sourceLineageList = getLineageForward(rois, sourceIndex);		
			ArrayList<Integer> targetLineageList = getLineageForward(rois, targetIndex);
			
			// break link for target;
			//removeLink (rois, oldSourceIndex, targetIndex, true);
			// remove old 
			if (removeOld) {	// replace sub-lineage
				removeLink (rois, sourceIndex, oldTargetIndex, false, true);	// keep the rest of source track
				removeLink (rois, oldSourceIndex, targetIndex, true, true);	// update the rest of target track
				// move target lineage into source track
				for (int idx : targetLineageList) {
					String name = rois[idx].getName();
					String trackID = "" + getTrack(name);
					String newName = name.replace("ID:"+trackID, "ID:"+sourceTrack);
					newName = newName.replace("-", "-"+sourceLineage);
					rois[idx].setName(newName);
					if (global) table.setValue( "Lineage ID", idx, getLineage(newName));
				}
				if (global) table.setValue( "Source Spot ID", targetIndex, sourceSpotID);
				if (global) table.setValue( "Source ROI index", targetIndex, sourceIndex);
				if (global) table.setValue( "link Cost", targetIndex, - 3);
			} else {	// keep old: add new sub-lineage
				removeLink (rois, oldSourceIndex, targetIndex, true, true);	// update the rest of target track
				// move target lineage into source track
				for (int idx : targetLineageList) {
					String name = rois[idx].getName();
					String trackID = "" + getTrack(name);
					String newName = name.replace("ID:"+trackID, "ID:"+sourceTrack);
					newName = newName.replace("-", "-"+sourceLineage+"1");
					rois[idx].setName(newName);
					if (global) table.setValue( "Lineage ID", idx, getLineage(newName));
				}
				if (global) table.setValue( "Source Spot ID", targetIndex, sourceSpotID);
				if (global) table.setValue( "Source ROI index", targetIndex, sourceIndex);
				if (global) table.setValue( "link Cost", targetIndex, - 3);
				// update original source track
				if (global) table.setValue( "Target Spot number", sourceIndex, 2 );
				for (int idx : sourceLineageList) {
					if (idx == sourceIndex) continue;
					String name = rois[idx].getName();
					//String trackID = "" + getTrack(name);
					String newName = name.replace("-"+sourceLineage, "-"+sourceLineage+"0");
					rois[idx].setName(newName);
					if (global) table.setValue( "Lineage ID", idx, getLineage(newName));
				}
			}
			//System.out.println("        old source:" + rois[sourceIndex].getName());
			//System.out.println("        old target:" + rois[oldTargetIndex].getName());
			//System.out.println("        new target:" + rois[newTargetIndex].getName());
			if (global) cellROIArray = rois;
			return rois;
		}



		private void correctLinks(ImagePlus imp, ResultsTable table, Roi[] rois, HashMap<Integer, ArrayList<Integer>> frameROIs,
				double minCost, double maxCount, double rangeXY, double rangeT, double minSimScore) {
			// get ROI statistics: area, X, Y, T, channel mean, channel stdDev
			// area-distribution, XYT-range, mean-distribution, stdDev-distribution
			System.out.println(" correct links:");
			double start = System.currentTimeMillis();
			
			//table = table;
			//cellROIArray = rois;
			
			int nROI = rois.length;
			HashMap<String, double[]> map = new HashMap<String, double[]>();
			double[] area = new double[nROI];
			double[] X = new double[nROI];	double[] Y = new double[nROI];	double[] T = new double[nROI];
			double[] major = new double[nROI];	double[] minor = new double[nROI];
			double[] C1mean = new double[nROI]; double[] C1stdDev = new double[nROI];
			double[] C2mean = new double[nROI]; double[] C2stdDev = new double[nROI];
			double[] C3mean = new double[nROI]; double[] C3stdDev = new double[nROI];
			for (int c=0; c<3; c++) {
				imp.setPositionWithoutUpdate(c+1, imp.getZ(), imp.getT());
				for (int i=0; i<nROI; i++) {
					imp.setRoi(rois[i], false);
					ImageStatistics stats = imp.getAllStatistics();
					if (c==0) {
						area[i] = stats.area;
						X[i] = stats.xCentroid; Y[i] = stats.yCentroid; T[i] = (double) rois[i].getTPosition();
						major[i] = stats.major; minor[i] = stats.minor;
						C1mean[i] = stats.mean; C1stdDev[i] = stats.stdDev;
					} else if (c==1) {
						C2mean[i] = stats.mean; C2stdDev[i] = stats.stdDev;
					} else { C3mean[i] = stats.mean; C3stdDev[i] = stats.stdDev;}
				}
			}
			// get normalization parameters;
			double[] areaMinMax = StatisticUtility.getMinMax ( area );
			double[] XMinMax = StatisticUtility.getMinMax (X);
			double[] YMinMax = StatisticUtility.getMinMax (Y);
			double[] TMinMax = StatisticUtility.getMinMax (T);
			
			double[] majorMinMax = StatisticUtility.getMinMax ( major );
			double[] minorMinMax = StatisticUtility.getMinMax ( minor );
			
			double[] C1meanMinMax = StatisticUtility.getMinMax ( C1mean );
			double[] C1stdDevMinMax = StatisticUtility.getMinMax ( C1stdDev );

			double[] C2meanMinMax = StatisticUtility.getMinMax ( C2mean );
			double[] C2stdDevMinMax = StatisticUtility.getMinMax ( C2stdDev );

			double[] C3meanMinMax = StatisticUtility.getMinMax ( C3mean );
			double[] C3stdDevMinMax = StatisticUtility.getMinMax ( C3stdDev );

			// get normalized values;
			for (int i=0; i<nROI; i++) {
				area[i] 	= StatisticUtility.normalizeDataByRange(area[i], areaMinMax[0], areaMinMax[1] );
				X[i]		= StatisticUtility.normalizeDataByRange(X[i], XMinMax[0], XMinMax[1] );
				Y[i]		= StatisticUtility.normalizeDataByRange(Y[i], YMinMax[0], YMinMax[1] );
				T[i]		= StatisticUtility.normalizeDataByRange(T[i], TMinMax[0], TMinMax[1] );
				major[i]	= StatisticUtility.normalizeDataByRange(major[i], majorMinMax[0], majorMinMax[1] );
				minor[i]	= StatisticUtility.normalizeDataByRange(minor[i], minorMinMax[0], minorMinMax[1] );
				C1mean[i]	= StatisticUtility.normalizeDataByRange(C1mean[i], C1meanMinMax[0], C1meanMinMax[1] );
				C1stdDev[i]	= StatisticUtility.normalizeDataByRange(C1stdDev[i], C1stdDevMinMax[0], C1stdDevMinMax[1] );
				C2mean[i]	= StatisticUtility.normalizeDataByRange(C2mean[i], C2meanMinMax[0], C2meanMinMax[1] );
				C2stdDev[i]	= StatisticUtility.normalizeDataByRange(C2stdDev[i], C2stdDevMinMax[0], C2stdDevMinMax[1] );
				C3mean[i]	= StatisticUtility.normalizeDataByRange(C3mean[i], C3meanMinMax[0], C3meanMinMax[1] );
				C3stdDev[i]	= StatisticUtility.normalizeDataByRange(C3stdDev[i], C3stdDevMinMax[0], C3stdDevMinMax[1] );
			}
			map.put( "area", area );	map.put( "X", X );	map.put( "Y", Y );	map.put( "T", T );
			map.put( "major", major );	map.put( "minor", minor );
			map.put( "C1mean", C1mean );	map.put( "C1stdDev", C1stdDev );
			map.put( "C2mean", C2mean );	map.put( "C2stdDev", C2stdDev );
			map.put( "C3mean", C3mean );	map.put( "C3stdDev", C3stdDev );

			double duration = (System.currentTimeMillis() - start) / 1000;
			System.out.println("   get statistics: " + duration + " seconds");
			
			
			// get high cost list
			start = System.currentTimeMillis();
			double[] costList = table.getColumnAsDoubles( table.getColumnIndex("link Cost") );
			ArrayList<Integer> highCostIndex = getHighCostIndex(costList, minCost, maxCount);
			System.out.println("   high cost list size: " + highCostIndex.size());
			duration = (System.currentTimeMillis() - start) / 1000;
			System.out.println("   get high cost spots: " + duration + " seconds");
				
			// debug: display 100 high cost list
			//for (int idx : highCostIndex) {
			//	System.out.println( " high cost index:" + idx + ", cost:"+costList[idx] + ", ROI:" + rois[idx].getName());
			//}
			// for each high cost spots, find its possible correct target:
			start = System.currentTimeMillis();
			int correctedCount = 0;
			int sameTarget = 0;
			int targetNotFound = 0;
			for (int i=0; i<highCostIndex.size(); i++) {
				int index = highCostIndex.get(i); // the old target ROI index
				
				//int oldTargetSpotID = (int) table.getValue( "Spot ID" , index);
				int sourceSpotID = (int) table.getValue( "Source Spot ID" , index);
				int sourceROIIndex = (int) table.getValue( "Source ROI index" , index);
				
				// skip more than 1 frame gap
				int t1 = (int) table.getValue ( "T", sourceROIIndex);
				int t2 = (int) table.getValue ( "T", index);
				if (t2-t1 != 1) { targetNotFound++; continue;}
				//System.out.println(" debug: target index:" + index + ", source index:" + sourceROIIndex);
				//System.out.println(" debug: target ROI:" + rois[index].getName() + ", source ROI:" + rois[sourceROIIndex].getName());
				
				if (sourceSpotID == -1) continue;
				
				int maybeTargetROIIndex = getPossibleTargetROIIndex (imp, sourceROIIndex, frameROIs, rois, map, rangeXY, rangeT, minSimScore);
				//if (maybeTargetROIIndex != -1)
					//System.out.println(" debug: maybeTargetROIIndex index:" + maybeTargetROIIndex + ", ROI:" + rois[maybeTargetROIIndex].getName());
				
				if (maybeTargetROIIndex == index) {
					sameTarget++;
					//println("	same target as before.");
				} else if (maybeTargetROIIndex != -1) {
					correctedCount++;
					// break the link between old source and old target
					//rois = removeLink (rois, maybeTargetROIIndex);
					// make the link between old source and new target
					rois = createLink (rois, sourceROIIndex, maybeTargetROIIndex, true, true);
					//System.out.println(" debug: high cost index:" + index + ", cost:" + costList[index]);
					
					// search for new source of the old target
					
					//int maybeSourceROIIndex = getPossibleSourceROIIndex (imp, index, frameROIs, rois, map, rangeXY, rangeT, minSimScore);
					// make the link between new source and old target
					//int oldTargetRoiIndex = getTargetROIIndex (maybeSourceROIIndex);
					// int oldTargetRoiIndex = maybeSourceROIIndex + 1;
					//rois = createLink (rois, maybeSourceROIIndex, oldTargetRoiIndex, index, false);
					
					//int maybeSourceIndex = getPossibleSource (imp, rois, index, map, searchRange, minSimScore);
					//}
				} else {
					targetNotFound++;
					//println("	target not found.");
				}	
			}
			duration = (System.currentTimeMillis() - start) / 1000;
			System.out.println("      corrected size: " + correctedCount);
			System.out.println("      same Target size: " + sameTarget);
			System.out.println("      target Not Found size: " + targetNotFound);
			System.out.println("   correct links cost: " + duration + " seconds");
			// sort the ROI array
			start = System.currentTimeMillis();
			cellROIArray = sortROIandTable(rois);
			// save updated result table to disk
			String newTablePath = tablePath;
			if (newTablePath.endsWith(".csv")) {
				int idx = newTablePath.lastIndexOf(".csv");
				newTablePath = newTablePath.substring(0, idx);
				newTablePath += "_AutoCorrect.csv";
			}
			try {
				table.saveAs(newTablePath);
			} catch (IOException e) {
				System.out.println("can not save updated result table");
				e.printStackTrace();
			}
			//cellROIArray = rois;
			//sortROI();
			refreshTrackManager();
			duration = (System.currentTimeMillis() - start) / 1000;
			System.out.println("   sort ROI array cost: " + duration + " seconds");
		}
		
		//
		private ArrayList<Integer> getHighCostIndex (double[] costList, double threshold, double maxCount) {
			// sort index of cost list by cost value (descendent)
			ArrayList<Integer> indices = new ArrayList<Integer>();
			for (int i=0; i<costList.length; i++) { indices.add(i); }
			Collections.sort(indices, new Comparator<Integer>() {
				@Override
				public int compare(Integer idx1, Integer idx2) {
					Double v1 = costList[idx1]; Double v2 = costList[idx2];
					return v2.compareTo(v1);
			}});
			// trim the high cost index by cost threshold, and maximum count
			ArrayList<Integer> highCostList = new ArrayList<Integer>();
			for (int idx : indices) {
				if (costList[idx] < threshold) break;
				highCostList.add(idx);
				if (highCostList.size() >= maxCount) break;
			}
			return highCostList;
		}
		
		// locate most similar ROI in the next 2 frames
		private int getPossibleTargetROIIndex (ImagePlus imp, int sourceIndex, 
				HashMap<Integer, ArrayList<Integer>> frameROIs, Roi[] rois,
				HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
			if (rois==null || rois.length<=sourceIndex) return -1;
			double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
			double[] center = rois[sourceIndex].getContourCentroid();
			double x0 = center[0]; double y0 = center[1];
			double t0 = (double) rois[sourceIndex].getTPosition();
			if (t0 == imp.getNFrames()) return -1;
			double[] value1 = StatisticUtility.getMeasurements ( imp, sourceIndex, map );
			double maxSim = 0; int targetROIIndex = -1;
			
			//System.out.println("\ndebug: t0:" + t0);
			ArrayList<Integer> roiList = new ArrayList<Integer> ();
			for (int i=0; i<dt; i++) {
				roiList.addAll( frameROIs.get( (int) (t0+1 + i) ) );
			}
			
			//System.out.println("debug: fStart[0]:" + fStart[0] + ",[1]:" + fStart[1]);
			//System.out.println("debug: fEnd[0]:" + fEnd[0] + ",[1]:" + fEnd[1]);
			for (Integer i : roiList) {
				// range control
				if (i>=rois.length)	break; 
				center = rois[i].getContourCentroid();
				double x1 = center[0]; double y1 = center[1];
				if (x1<x0-dx || x1>x0+dx) continue;
				if (y1<y0-dy || y1>y0+dy) continue;
				
				double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
				//double simScore = getCosineSimilarity (value1, value2);
				double simScore = StatisticUtility.getJSD (value1, value2);
				if (simScore<maxSim) continue;
				maxSim = simScore;
				targetROIIndex = i;
			}
			if (maxSim < minSimScore) targetROIIndex = -1;
			return targetROIIndex;
		}
		// locate most similar ROI in the previous frames
		/*
		private int getPossibleSourceROIIndex (ImagePlus imp, int targetIndex, 
				HashMap<Integer, ArrayList<Integer>> frameROIs, Roi[] rois,
				HashMap<String, double[]> map, double rangeXY, double rangeT, double minSimScore) {
			if (rois==null || rois.length<=targetIndex) return -1;
			int maxT = imp.getNFrames();
			double dx = rangeXY; double dy = rangeXY; double dt = rangeT;
			double[] center = rois[targetIndex].getContourCentroid();
			double x0 = center[0]; double y0 = center[1];
			double t0 = (double) rois[targetIndex].getTPosition();
			double[] value1 = StatisticUtility.getMeasurements ( imp, targetIndex, map );
			//println("	value1: " + value1);
			double maxSim = 0; int sourceROIIndex = -1;
			
			ArrayList<Integer> roiList = new ArrayList<Integer> ();
			for (int i=0; i<dt; i++) {
				roiList.addAll( frameROIs.get( (int) (t0-1 - i) ) );
			}

			for (Integer i : roiList) {
				// range control
				if (i>=rois.length)	break; 
				center = rois[i].getContourCentroid();
				double x1 = center[0]; double y1 = center[1];
				if (x1<x0-dx || x1>x0+dx) continue;
				if (y1<y0-dy || y1>y0+dy) continue;
				
				double[] value2 = StatisticUtility.getMeasurements ( imp, i, map );
				//double simScore = getCosineSimilarity (value1, value2);
				double simScore = StatisticUtility.getJSD (value1, value2);
				if (simScore<maxSim) continue;
				maxSim = simScore;
				sourceROIIndex = i;
			}
			if (maxSim < minSimScore) sourceROIIndex = -1;
			//else println("	max similarity score: " + maxSim);
			return sourceROIIndex;
		}
		*/
		/*
		private int getSourceSpotIndex (int index) {
			return getSourceSpotIndex( table, index );
		}
		*/
		private int getSourceSpotIndex (ResultsTable table, int index) {
			if (table==null) return -1;
			if (index<0 || index >= table.size()) return -1;
			int sourceID = (int) table.getValue( "Source Spot ID", index);
			if (index !=0 ) {
				int tryID = (int) table.getValue( "Spot ID", index-1);
				if (tryID == sourceID) return (index-1);
			}
			for (int i=0; i<table.size(); i++) {
				int spotID = (int) table.getValue( "Spot ID", i);
				if (spotID == sourceID) return i;
			}
			return -1;
		}
		/*
		private int getSpotIDFromRoi (Roi[] rois, int roiIndex, SpotCollection spots, double pixelSize) {
			if (rois==null || roiIndex<0 || rois.length<=roiIndex || spots==null) return -1;
			Roi roi = rois[roiIndex];
			int tPos = roi.getTPosition();
			Iterator<Spot>	iter = spots.iterator(tPos, false); // check frame position
			boolean first = true;
			while (iter.hasNext()) {
				Spot spot = iter.next();
				if (first) {
					//System.out.println("debug: 924 get spot index, first spot on frame " + tPos + " : " + spot.ID());
					first = false;
				}
				double x = spot.getFeature("POSITION_X") / pixelSize;
				double y = spot.getFeature("POSITION_Y") / pixelSize;
				
				if (roi.contains((int)x, (int)y)) {
					return spot.ID();
				}
			}
			return -1;
		}
		
		private int getROIIndex (Roi[] rois, Spot spot, double pixelSize) {
			double x = spot.getFeature("POSITION_X") / pixelSize;
			double y = spot.getFeature("POSITION_Y") / pixelSize;
			double t = spot.getFeature("FRAME") + 1;
			return getROIIndex (rois, x, y, t);
		}
		private int getROIIndex (ArrayList<Roi> frameRois, Spot spot, double pixelSize) {
			double x = spot.getFeature("POSITION_X") / pixelSize;
			double y = spot.getFeature("POSITION_Y") / pixelSize;
			double t = spot.getFeature("FRAME") + 1;
			return getROIIndex (frameRois, x, y, t);
		}
		private int getROIIndex (double x, double y, double t) {
			return getROIIndex (cellROIArray, x, y, t);
		}
		
		private int getROIIndex (Roi[] rois, double x, double y, double t) {// x, y in pixel unit
			if (rois==null || rois.length==0) return -1;
			for (int i=0; i<rois.length; i++) {
				if (rois[i].getTPosition()!=t) continue;
				if (rois[i].contains((int)x, (int)y)) return i;
			}
			return -1;
		}
		private int getROIIndex (ArrayList<Roi> rois, double x, double y, double t) {// x, y in pixel unit
			if (rois==null || rois.size()==0) return -1;
			for (int i=0; i<rois.size(); i++) {
				if (rois.get(i).getTPosition()!=t) continue;
				if (rois.get(i).contains((int)x, (int)y)) return i;
			}
			return -1;
		}
		*/
		private int getROIIndex (Roi[] rois, String roiName) {
			if (rois==null || rois.length==0) return -1;
			for (int i=0; i<rois.length; i++) {
				if (rois[i].getName() == roiName) return i;
			}
			return -1;
		}

}
