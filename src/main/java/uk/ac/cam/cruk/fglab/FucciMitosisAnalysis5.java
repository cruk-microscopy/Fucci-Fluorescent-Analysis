package uk.ac.cam.cruk.fglab;


import java.util.ArrayList;
import java.util.HashMap;

import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.TrackMate;


import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.PlugIn;

/**
 * Plugin description goes here
 *
 * @author Ziqiang Huang
 */
public class FucciMitosisAnalysis5 implements PlugIn {
	
	/*
	 * Generate cell ROI:
	 * 			input:	channel, (cell diameter, quality threshold)
	 * 			output: cell ROI
	 * 
	 * Optional: classify 
	 * Construct TrackMate:
	 * 			input:	spot (from StarDist ROI / TrackMate), (linkingMax, frameGap)
	 * 			output:	1st trackmate model
	 * 
	 * Modify Tracks:
	 * 			input: track spots, (minimum cost for wrong link, minimum similarity score, search range)
	 * 			compute: for high cost links, compute similar cells as its new target/source
	 * 
	 * Configure ROI and lineage:
	 * 
	 * 
	 * Export result
	 * 
	 * 
	 */
	
	
	
	
	
	//private ImagePlus imp;
	
	private static Roi[] cellRoiArray;
	private static ResultsTable table;
	
	private final String TABLE_NAME = "Tracking Data Table";
	private final String XML_NAME = "Track Model";
	private final String CELL_ROI_NAME = "Tracking Cell ROI";
	//private static ArrayList<Roi[]> cellRois;
	//protected static Roi[] cellRois;

	@Override
	public void run(String arg) {
		// get user input with parameter dialog
		ParameterDialog params = new ParameterDialog();
		if (!params.mainDialog())	return;
		
		// timing the start
		IJ.log(GetDateAndTime.getCurrentDate());
		IJ.log(GetDateAndTime.getCurrentTime());
		long start = GetDateAndTime.getCurrentTimeInMs();
		
		// load data as image plus
		ImagePlus imp = params.image;
		
		// print image information to imagej log window
		GetImageInfo.getInfo(imp);

		// get cell ROIs
		cellRoiArray = params.cellRois;
		
			// shortcut to only save StarDist generated cell ROIs for later usage
			/*
			if (params.saveROI && !params.saveTable && !params.saveModel && !params.viewInGUI) {
				IJ.log(" Only generate and save cell ROI. Skip tracking and subsequent analysis.");
				ROIUtility.saveRois(cellRoiArray, params.saveDir + CELL_ROI_NAME + " (" + imp.getTitle() + ")_noTracking.zip");
				if (params.showManager) { 
					ROIUtility.showRoisWithManager(cellRoiArray, imp);
					imp.deleteRoi(); imp.setOverlay(null); imp.show(); imp.getWindow().setVisible(true);
				}
				return;
			}
			*/

		// run TrackMate
		TrackMateUtility tu = new TrackMateUtility (imp, params);
		TrackMate trackmate;
		
		if (cellRoiArray==null)		trackmate = tu.trackmateWithLogSpot();	// no predefined cell ROI, run LoG spot detection
		else 						trackmate = tu.trackmateWithCellRoi();	// use pre-defined ROI to construct tracking
		
		if (trackmate==null ) return;
		Model model = trackmate.getModel();
	 	IJ.log("      Tracking completed.");
	 	IJ.log(String.format("      Found %d spots in %d tracks.", 
	 			model.getSpots().getNSpots(true), model.getTrackModel().nTracks(true)));
	 			
		//  Further analyze Fucci fluorescence and mitosis
	 	LineageAnalysis la = new LineageAnalysis(imp, cellRoiArray, model);
	 	la.run();
		table = la.getResults();
		cellRoiArray = la.getRois();
		IJ.log("      Lineage analysis completed.");
		
		// save results
		// save data table
		if (params.saveTable) {
			table.save(params.saveDir + TABLE_NAME + " (" + imp.getTitle() + ").csv");
		}
		// save all track spots in Roimanager to roiset.zip
		if (params.saveROI) {
			ROIUtility.saveRois(cellRoiArray, params.saveDir + CELL_ROI_NAME + " (" + imp.getTitle() + ").zip");
		}
		// save trackmate model
		if (params.saveModel) {
			TrackMateUtility.saveTrackmateModel(trackmate, params.saveDir + XML_NAME +  " (" + imp.getTitle() + ").xml");
		}
		
		
		
		// display result
		if (params.silentMode)	{ imp.close(); return; }
		// prepare and show result table
		table.setPrecision( 3 );	table.showRowIndexes(true);		table.show( TABLE_NAME );
		
		// show image
		imp.deleteRoi();	imp.setOverlay(null);	imp.show();		imp.getWindow().setVisible(true);
			
		// show ROI Manager
		if (params.showManager) { ROIUtility.showRoisWithManager(cellRoiArray, imp); }
		
		// show TrackMate GUI
		if (params.viewInGUI) { TrackMateUtility.display_results_in_GUI(imp, trackmate); }
		
		// clear cache and wrap up
		System.gc();
		long end = GetDateAndTime.getCurrentTimeInMs();
		String duration = GetDateAndTime.getDuration(end-start);
		IJ.log("Fucci mitosis analysis completed after : " + duration);
	}
	
	public static void main(String[] args) {
		/*
		if (IJ.versionLessThan("1.52f")) System.exit(0);

		String [] ij_args = 
			{ "-Dplugins.dir=C:/Fiji.app/plugins",
			"-Dmacros.dir=C:/Fiji.app/macros" };

		ij.ImageJ.main(ij_args);
		*/
		//fiji fj = new fiji();

		FucciMitosisAnalysis5 fma = new FucciMitosisAnalysis5();
		fma.run(null);
		
	}
}
