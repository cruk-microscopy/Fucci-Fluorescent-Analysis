package uk.ac.cam.cruk.fglab;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.traverse.GraphIterator;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.TrackModel;
import fiji.plugin.trackmate.graph.TimeDirectedNeighborIndex;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

public class LineageAnalysis {

	protected int numLineageMax = 2;
	protected double spotRadius = 10;	// default spot radius for display
	
	protected ImagePlus imp;
	protected Model model;
	protected Roi[] cellRoiArray;
	
	protected ArrayList<Roi> roiList;
	
	protected ResultsTable table;
	
	
	public LineageAnalysis (ImagePlus imp, Roi[] cellRoiArray, Model model) {
		this.imp = imp;
		this.cellRoiArray = cellRoiArray;
		this.model = model;
	}
	
	public void setLineageMax (int numLineageMax) {
		this.numLineageMax = numLineageMax;
	}
	
	public Roi[] getRois () {
		if (roiList==null || roiList.size()==0) return null;
		Roi[] roiArray = new Roi[roiList.size()];
		for (int i=0; i<roiList.size(); i++)
			roiArray[i] = roiList.get(i);
		//return ROIUtility.sortRoi(roiArray);
		return roiArray;
	}
	public ResultsTable getResults () {
		return this.table;
	}
	
	public void run () {
		
		
		//RoiManager rm = ROIUtility.prepareManager();

		// prepare temporary variables for mitosis detection and labellings
		int nZeros = String.valueOf(this.model.getSpots().getNSpots(true)).length();	// padding left with zero for spot index
		//ArrayList<Integer> mitosisTrackIDs = new ArrayList<Integer>();
		//ArrayList<Spot> mitosisSpots = new ArrayList<Spot>();
		//ArrayList<Integer> mitosisRoiIDs = new ArrayList<Integer>();
		//Map<String, ArrayList<Integer>> mitosisCells = new HashMap<String, ArrayList<Integer>>();
		
		//boolean isMitosis = false;
		//boolean cellIsMitosis = false;
		//int nMitosis = 0;
		//ImagePlus impCrop = new ImagePlus();
		/*
		 *  Construct ResultTable and RoiManager
		 */
			//final String XML_NAME = "Track Model";
			//final String TABLE_NAME = "Tracking Data Table";
			//final String CELL_ROI_NAME = "Tracking Cell ROI";
			//final String SINGLE_TRACK_NAME = "Single Cell Tracks";
			//final String MITOSIS_TRACK_NAME = "Mitosis Tracks";
		this.roiList = new ArrayList<Roi>();
		this.table = new ResultsTable();
			// prepare RoiManager for operation
			//rm.reset();
			//int roiCount = 0;
			
			/*
			for (int i=0; i<cellROIArray.length; i++) {
				rm.add(cellROIArray[i], -1);
			}
			rm.runCommand("Save", ParameterDialog.saveDir + File.separator + CELL_ROI_NAME + " (" + imp.getTitle() + ")_debug1.zip");
			rm.reset();
			*/
		// prepare arraylist for mitosis track
		//ArrayList<Spot> mitosisSpotList = new ArrayList<Spot>();
		//ArrayList<Integer> mitosisTrackIdx = new ArrayList<Integer>();

		TrackModel tm = this.model.getTrackModel();
		Set<Integer> trackIDs = tm.trackIDs(true);
		TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();
		double pixelSize = this.imp.getCalibration().pixelWidth;
		
		// prepare temporary variables for single track and lineage extraction
		//Map<String, ArrayList<Integer>> singleCells = new HashMap<String, ArrayList<Integer>>();
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
		//System.out.println("debug: numC: " + numC);
		// we iterate through all starting spot (spot has no predecessor)
		
		// we first get all track and lineage ID translated into ROI names
		ArrayList<Double> costList = new ArrayList<Double>();
		for (Spot startSpot : startSpots) {
			int trackID = tm.trackIDOf(startSpot);
			// 3 variables for temporary store track segments to form the single tracks
			//ArrayList<ArrayList<Integer>> segments = new ArrayList<ArrayList<Integer>>();
			//ArrayList<Integer> seg = new ArrayList<Integer>();
			String lineageID = String.valueOf(trackID) + "-";
			GraphIterator<Spot, DefaultWeightedEdge> iter = tm.getDepthFirstIterator(startSpot, true);
			while (iter.hasNext()) {
				Spot currentSpot = iter.next();
				String roiName = "ROI:" + String.format("%0"+String.valueOf(nZeros)+"d", currentSpot.ID()) + ", ID:" + lineageID;
				
				double x = currentSpot.getFeature("POSITION_X");
				double y = currentSpot.getFeature("POSITION_Y");
				double t = currentSpot.getFeature("FRAME") + 1;
				double d = currentSpot.getFeature("ESTIMATED_DIAMETER")!=null ? currentSpot.getFeature("ESTIMATED_DIAMETER") : spotRadius*2;
				
				// generate circle selection and add to RoiManager
				int idx = getRoiIndex (this.cellRoiArray, x/pixelSize, y/pixelSize, t);
				
				Roi spotRoi = (idx==-1) ? new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize) : cellRoiArray[idx];
				//if (spotRoi.getType()== Roi.OVAL) {System.out.println(roiName + " is oval.");}
				spotRoi.setPosition(0, 1, (int)t);
				spotRoi.setName(roiName);
				this.roiList.add(spotRoi);
				//System.out.println("roiList " + roiList.size() + " : " + roiName);
				//rm.add(imp, spotRoi, currentSpot.ID());	// add roi with imp to RoiManager
				//rm.rename(rm.getCount()-1, roiName);	
				
				
				//seg.add(rm.getCount()-1);
				// try, get link cost between source and current (-1 if no source)
				double linkCost = -1;
				// check segment condition
				int sourceSpotID = -1;
				List<Spot> predecessor = neighborIndex.predecessorListOf(currentSpot);
				if (predecessor.size() == 1) {
					sourceSpotID = predecessor.get(0).ID();
					linkCost = tm.getEdgeWeight(tm.getEdge(predecessor.get(0), currentSpot));
				}
				costList.add(linkCost);
				
				int sourceROIIndex = (sourceSpotID==-1) ? -1 : getRoiIndex(this.cellRoiArray, predecessor.get(0), pixelSize);

				
				int nTarget = neighborIndex.successorsOf(currentSpot).size();
				
				this.table.incrementCounter();
				this.table.addValue( "Spot ID", currentSpot.ID() );
				this.table.addValue( "Source Spot ID", sourceSpotID );
				this.table.addValue( "Source ROI index", sourceROIIndex);
				this.table.addValue("link Cost", linkCost);
				this.table.addValue( "Target Spot number", nTarget );
				//table.addValue( "Track ID", trackID );
				this.table.addValue( "Lineage ID", lineageID);
				this.table.addValue( "X", x );
				this.table.addValue( "Y", y );
				this.table.addValue( "T", t );
				
				switch(nTarget) {
					case 2: // cell has two daughter cells
						lineageID += "0";
						//segments.add(seg);
						//seg = new ArrayList<Integer>();
						break;
					case 0: // current cell ends
						// get stored segments into a single track, and then add to cell list
						//ArrayList<Integer> singleTracks = new ArrayList<Integer>();
						//for (ArrayList<Integer> segment : segments) {
						//	singleTracks.addAll(segment);
						//}
						//singleTracks.addAll(seg);
						//singleCells.put(lineageID, singleTracks);
						//seg = new ArrayList<Integer>();
						// clear current seg buffer, remove last part of the segments
						if (lineageID.endsWith("0")) {
							lineageID = lineageID.substring(0, lineageID.length()-1) + "1";
						} else if (lineageID.endsWith("1")) {
							//lineageID = lineageID.substring(0, lineageID.length()-1);
							while(lineageID.endsWith("1")) {
								lineageID = lineageID.substring(0, lineageID.length()-1);
								//if (segments.size()!=0)
								//	segments.remove(segments.size()-1);
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
				
				
				this.imp.deleteRoi();
				
				
				
				
				Roi tempRoi = new OvalRoi((x-d/2)/pixelSize,(y-d/2)/pixelSize, d/pixelSize, d/pixelSize);
				tempRoi.setPosition(0, 1, (int)t);
				// use the circle ROI to crop around original image and do mitosis check
				
				this.imp.setRoi(tempRoi, false);
				for (int i=0; i<imp.getNChannels(); i++) {
					imp.setPositionWithoutUpdate(i+1, tempRoi.getZPosition(), tempRoi.getTPosition());
					table.addValue("C"+String.valueOf(i+1), imp.getRawStatistics().mean);
				}
			}
		}
		
		//frameROIs = makeFrameROIs(cellROIArray);
		for (int i=0; i<this.table.size(); i++) {
			int sourceSpotID = (int) this.table.getValue( "Source Spot ID" , i );
			int sourceIndex = getSourceSpotIndex (this.table, i);
			if (sourceIndex == -1) {
				this.table.setValue( "Source ROI index", i, -1);
				continue;
			}
			double x = this.table.getValue( "X", sourceIndex ); 
			double y = this.table.getValue( "Y", sourceIndex ); 
			double t = this.table.getValue( "T", sourceIndex );
			int sourceROIIndex = (sourceSpotID==-1) ? -1 : getRoiIndex(this.cellRoiArray, x/pixelSize, y/pixelSize, t);
			this.table.setValue( "Source ROI index", i, sourceROIIndex);
		}
		
	}
	
	public void buildLineage (ImagePlus imp, Model model, Roi[] cellRois) {
		
		// prepare ROI Manager
		RoiManager rm = ROIUtility.prepareManager(true);
		
		//
		TrackModel tm = model.getTrackModel();
		Set<Integer> trackIDs = tm.trackIDs(true);
		int nZeros = String.valueOf(model.getSpots().getNSpots(true)).length();
		TimeDirectedNeighborIndex neighborIndex  = tm.getDirectedNeighborIndex();
		double pixelSize = imp.getCalibration().pixelWidth;
		int roiCount = 0;
		
		// get starting spot of each track
		ArrayList<Spot> startSpots = new ArrayList<Spot>();
		for (final Integer trackID : trackIDs) {
			Set<Spot> spots = tm.trackSpots(trackID);
			for (Spot spot : spots) {
				int nSource = neighborIndex.predecessorsOf(spot).size();
				if (nSource==0) {
					startSpots.add(spot);
				}
			}
		}

		// iterate through all starting spot (spot has no predecessor)
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
	}
	
	// get ROI array index of source spot, index is 
	private int getSourceSpotIndex (ResultsTable table, int index) {
		if (table==null) return -1;
		if (index >= table.size()) return -1;
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
	// get correponding ROI from array, by LoG Spot center cooridinate x, y, t
	protected int getRoiIndex (Roi[] rois, Spot spot, double pixelSize) {
		double x = spot.getFeature("POSITION_X") / pixelSize;
		double y = spot.getFeature("POSITION_Y") / pixelSize;
		double t = spot.getFeature("FRAME") + 1;
		return getRoiIndex (rois, x, y, t);
	}
	// get corresponding ROI from array, by ROI center coordinate x, y, t
	protected int getRoiIndex (Roi[] rois, double x, double y, double t) { // x, y in pixel unit
		if (rois==null || rois.length==0) return -1;
		for (int i=0; i<rois.length; i++) {
			if (rois[i].getTPosition()!=t) continue;
			if (rois[i].contains((int)x, (int)y)) return i;
		}
		return -1;
	}
	
}
