package uk.ac.cam.cruk.fglab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.jgrapht.graph.DefaultWeightedEdge;

import fiji.plugin.trackmate.Spot;
import fiji.plugin.trackmate.TrackModel;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.Concatenator;
import ij.plugin.Duplicator;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;


public class trackStackViewer {

	public static void saveTrackAsTable(
			ImagePlus imp,
			TrackModel tm,
			List<org.jgrapht.graph.DefaultWeightedEdge> edges,
			double roiDiameter,
			int roiChannel,
			ArrayList<Spot> mitosisSpot,
			String savePath
			) {
		
	}
	public static void saveTrackAsRoi(
			ImagePlus imp,
			TrackModel tm,
			List<org.jgrapht.graph.DefaultWeightedEdge> edges,
			double roiDiameter,
			int roiChannel,
			ArrayList<Spot> mitosisSpot,
			double[] thresholds,
			String modelPath,
			String savePath
			) {
		// prepare RoiManager for operation
		Roi[] rois = RoiManagerUtility.managerToRoiArray();
		RoiManagerUtility.resetManager();
		RoiManager rm = RoiManager.getInstance2();
		
		double pixelSize = imp.getCalibration().pixelWidth;
		
		double t_pre = 0;
		double t_current = 0;
		double x = 0;
		double y = 0;
		//Spot firstSpot = edges.get(0).getSource();
		Spot spot;
		String mitosisSpotName = null;
		int numMito = 1;
		//int spotID
		ListIterator<DefaultWeightedEdge> edgeIterator = edges.listIterator();

		// change! 
		final ResultsTable table = new ResultsTable();
		// change
		
		while (edgeIterator.hasNext()) {
			DefaultWeightedEdge edge = edgeIterator.next();
			if (!edgeIterator.hasPrevious()) {
				spot = tm.getEdgeSource(edge);
			} else {
				spot = tm.getEdgeTarget(edge);
			}

			t_current = spot.getFeature("FRAME")+1;
			x = spot.getFeature("POSITION_X"); y = spot.getFeature("POSITION_Y");
			
			OvalRoi r = new OvalRoi((x-roiDiameter/2)/pixelSize,(y-roiDiameter/2)/pixelSize, roiDiameter/pixelSize, roiDiameter/pixelSize);
			r.setPosition(roiChannel, 1, (int)t_current);
			rm.add(imp, r, spot.ID());
			
			// change!
			table.incrementCounter();
			table.addValue( "Spot ID", spot.ID() );
			//table.addValue("TrackID", value);
			table.addValue( "T", t_current );
			table.addValue( "X", x );
			table.addValue( "Y", y );
			imp.setRoi(r);
			imp.setT((int)t_current);
			imp.setC(1);
			table.addValue( "C1 intensity", imp.getStatistics().mean);
			imp.setC(2);
			table.addValue( "C2 intensity", imp.getStatistics().mean);
			imp.setC(3);
			table.addValue( "C3 intensity", imp.getStatistics().mean);			
			
			mitosisSpotName = String.valueOf(spot.ID())+" mitosis:"+ String.valueOf(numMito);
			
			if (mitosisSpot.contains(spot)) {
				numMito++;
				rm.rename(rm.getCount()-1, mitosisSpotName);
				table.addValue( "mitosis spot", "yes");
			} else {
				if (MitosisUtility.mitosisCheckQuick(imp, r, 1, 3, thresholds)) {
					numMito++;
					rm.rename(rm.getCount()-1, mitosisSpotName);
					table.addValue( "mitosis spot", "yes");
				} else {
					rm.rename(rm.getCount()-1, String.valueOf(spot.ID()));
					table.addValue( "mitosis spot", "no");
				}
			}
		}
		
		table.setPrecision( 3 );
		String TABLE_NAME = savePath.substring(0, savePath.length()-4) + ".csv";
		try {
			table.saveAs(TABLE_NAME);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		//MitosisUtility.mitosisCheckWithRoiManager(imp, modelPath);
		
		rm.runCommand("Save", savePath);
		
		RoiManagerUtility.resetManager();
		RoiManagerUtility.roiArrayToManager(rois, true, false);
		return;	
		
	}
	
	public static ImagePlus saveTrackAsStack(
			ImagePlus imp,
			TrackModel tm,
			List<org.jgrapht.graph.DefaultWeightedEdge> edges
			) {

			double pixelSize = imp.getCalibration().pixelWidth;
			double dTrackMax = 15; // use 15 micron as default
			dTrackMax *= 3;
			int dMax = (int)(dTrackMax);
			
			ArrayList<ImagePlus> imps = new ArrayList<ImagePlus>();
			
			double t_pre = 0;
			double t_current = 0;

			//Spot firstSpot = edges.get(0).getSource();
			Spot spot;
			ListIterator<DefaultWeightedEdge> edgeIterator = edges.listIterator();
			ImagePlus imp2 = null;	// image plus to store cropped image around spot
			while (edgeIterator.hasNext()) {
				DefaultWeightedEdge edge = edgeIterator.next();
				if (!edgeIterator.hasPrevious()) {
					spot = tm.getEdgeSource(edge);
				} else {
					spot = tm.getEdgeTarget(edge);
				}

				t_current = spot.getFeature("FRAME");
				if (t_current == t_pre) {
					continue;
				} else {
					t_pre = t_current;
				}
				
				double x = spot.getFeature("POSITION_X");
				double y = spot.getFeature("POSITION_Y");
				imp.setT((int)t_current);
				int xBounds = (int)((x-dTrackMax/2)/pixelSize);
				int yBounds = (int)((y-dTrackMax/2)/pixelSize);
				imp.setRoi(xBounds, yBounds, dMax, dMax);
				imp2 = new Duplicator().run(imp, 1, 3, 1, 1, (int)t_current, (int)t_current);
				IJ.run(imp2, "Canvas Size...", "width="+String.valueOf(dMax)+" height="+String.valueOf(dMax)+" position=Center zero");
				if (imp2 != null)
					imps.add(imp2); 	
			}
			imp2.close();
			ImagePlus[] impsA = imps.toArray(new ImagePlus[0]);
			ImagePlus impFinal = Concatenator.run(impsA);
			return impFinal;	
		}
	
}
