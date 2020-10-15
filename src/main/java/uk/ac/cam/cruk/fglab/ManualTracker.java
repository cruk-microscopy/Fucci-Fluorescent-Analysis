package uk.ac.cam.cruk.fglab;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Insets;
import java.awt.Polygon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import javax.swing.BoxLayout;
import javax.swing.GroupLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;

import org.scijava.prefs.DefaultPrefService;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.KeyListener;

import ij.blob.ManyBlobs;

import ij.IJ;
import ij.gui.GUI;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.OvalRoi;
import ij.gui.PointRoi;
import ij.gui.PolygonRoi;
import ij.gui.Roi;
import ij.gui.RoiListener;
import ij.gui.WaitForUserDialog;
import ij.gui.YesNoCancelDialog;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.Duplicator;
import ij.plugin.PlugIn;
import ij.plugin.frame.PlugInFrame;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;

public class ManualTracker implements PlugIn {

	// parameters for source image
	final double oneHalfDayInSeconds = 1.296e5;
	protected ImagePlus sourceImage = null;
	protected int bfChannel;
	protected int numC;
	protected int numZ;
	protected int numT;
	protected int posC;
	protected int posZ;
	protected int posT;
	protected double cropSize = 20.0d; // micron
	// parameters for ROIs
	ArrayList<ArrayList<Roi>> tracks = new ArrayList<ArrayList<Roi>>();
	ArrayList<Roi> currentTrack = new ArrayList<Roi>();
	ArrayList<Integer> trackSize = new ArrayList<Integer>();
	ArrayList<Integer> trackID = new ArrayList<Integer>();
	int currentTrackID = 0;
	protected RoiListener listener;
	protected PointRoi newManualPoint;
	int startFrame;
	int currentFrame;
	
	protected boolean changeRoiShapeAll = true;
	protected final String[] roiShapes = {"Point", "Circle", "Cell outline"};
	protected int roiShape = 0;
	protected double ovalRoiRadius = 10.0;
	
	// parameters for tracking
	protected final String[] trackerOption = {"Manual", "Semi-Auto", "Auto"};
	protected final static String[] thresholdMethods = 
		{"Default","Otsu","Triangle","Huang","Intermodes","IsoData",
				"IJ_IsoData","MaxEntropy","Mean","MinError","Minimum","Moments",
				"Li","Percentile","RenyiEntropy","Shanbhag","Yen"};
	protected int autoMethod = 1;
	protected boolean doManualTrack = false;
	private volatile boolean doAutoTrack = false;
	protected int trackMode = 0;
	protected int autoTrackChannel = 3;
	protected boolean autoTrackForward = true;
	protected long autoTrackDisplayRate = 40; //ms (20-200)
	protected String autoTrackMethod = "Otsu";
	
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
	protected final Dimension textAreaMax = new Dimension(300, 150);
	protected final Dimension tablePreferred = new Dimension(260, 100);
	protected final Dimension tableMax = new Dimension(260, 150);
	protected final Dimension panelTitleMax = new Dimension(500, 30);
	protected final Dimension panelMax = new Dimension(200, 400);
	protected final Dimension panelMin = new Dimension(280, 200);
	protected final Dimension buttonSize = new Dimension(90, 10);
	
	protected JButton btnManualTrack;
	protected JButton btnAutoTrack;
	protected JButton btnCfgAuto;
	
	protected JButton btnChangeRoiShape;
	protected JButton btnDelBefore;
	protected JButton btnDelAfter;
	
	protected JButton btnViewPlot;
	protected JButton btnViewTable;
	
	protected JButton btnFindGap;
	
	protected JTextArea sourceInfo;
	
	 public class autoTrackThread extends Thread {
		public void run() {
			while (doAutoTrack) {
				try {
					autoTrack();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt(); // very important
					break;
				}
			}
			doAutoTrack = false;
		}
	}
	 
	public void prepareSourceImage() {
		// get active image or ask user for a image
		int nImage = WindowManager.getImageCount();
		if (nImage==0) {
			sourceImage = IJ.openImage();
		} else {
			sourceImage = WindowManager.getCurrentImage();
		}
		if (sourceImage==null) return;
		sourceImage.show();
		Roi oriRoi = sourceImage.getRoi(); sourceImage.deleteRoi();
		sourceImage.getWindow().setVisible(false);
		// get image information
		posC = sourceImage.getC();
		posZ = sourceImage.getZ();
		posT = sourceImage.getT();
		numC = sourceImage.getNChannels();
		numZ = sourceImage.getNSlices();
		numT = sourceImage.getNFrames();
	 	// reset visible channel, and reset BC for each fluorescent channel
		bfChannel = numC;
		String activeChannel = "";
		for (int c=1; c<=numC; c++) {
			sourceImage.setC(c);
			if (c!=bfChannel) {
				
				activeChannel += "1";
			} else {
				activeChannel += "0";
			}
		}
		sourceImage.setActiveChannels(activeChannel);
		// take 1.5 day time point to adjust brightness and contrast
		int oneHalfDayPoint = numT;
		Calibration cal = sourceImage.getCalibration();
		if (cal.getTimeUnit().toLowerCase().startsWith("sec"))
			oneHalfDayPoint = Math.min((int)(oneHalfDayInSeconds/cal.frameInterval), oneHalfDayPoint);
		sourceImage.setT(oneHalfDayPoint);
		for (int c=1; c<numC; c++) {
			sourceImage.setC(c);
			IJ.resetMinAndMax(sourceImage);
			IJ.run(sourceImage, "Enhance Contrast", "saturated=0.25");
		}
		sourceImage.setT(posT);
		sourceImage.setRoi(oriRoi);
		sourceImage.getWindow().setVisible(true);
		return;
	}
	
	public void addPanelToFrame (
			Frame f
			) throws Exception {
		// parse current active image in Fiji


		// create a parent panel for both title and content panels
		JPanel parentPanel = new JPanel();
		parentPanel.setBorder(border);
		parentPanel.setBackground(f.getBackground());
		parentPanel.setLayout(new BoxLayout(parentPanel, BoxLayout.Y_AXIS));
		//parentPanel.add(titlePanel, BorderLayout.NORTH);
		
		// create and configure the content panel
		JPanel contentPanel = new JPanel();
		contentPanel.setBorder(border);
		contentPanel.setBackground(panelColor);
		contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
		
		// create and configure the title panel "Sample image and ROIs"
		JLabel title = new JLabel("Image Info");
		title.setFont(panelTitleFont);
		title.setForeground(panelTitleColor);
		contentPanel.add(title);
		title.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		sourceInfo = new JTextArea();
		sourceInfo.setMaximumSize(textAreaMax);
		sourceInfo.setEditable(false);
		contentPanel.add(sourceInfo);
		sourceInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		/*
		JComboBox trackOptionBox = new JComboBox(trackerOption);
		trackOptionBox.setSelectedIndex(0);
		trackOptionBox.setMaximumSize(new Dimension(150, 30));
		trackOptionBox.addActionListener(new ActionListener() {
		    @Override
		    public void actionPerformed(ActionEvent event) {
		        //JComboBox<Integer> combo = (JComboBox<Integer>) event.getSource();
		    	trackMode = trackOptionBox.getSelectedIndex();
		    }
		});
		contentPanel.add(trackOptionBox);
		trackOptionBox.setAlignmentX(Component.CENTER_ALIGNMENT);
		*/
		
		JPanel buttonPanel = new JPanel();
		JButton btnRefresh = new JButton("refresh image");
		btnManualTrack = new JButton("start manual tracking");
		//JButton btnEndTrack = new JButton("finish current track");
		btnAutoTrack = new JButton("start auto tracking");
		btnCfgAuto = new JButton("configure auto tracking");
		
		btnChangeRoiShape = new JButton("change ROI shape");
		btnDelBefore = new JButton("delete all <<<");
		btnDelAfter = new JButton("delete all >>>");
		
		btnViewPlot = new JButton("plot tracked cells");
		btnViewTable = new JButton("create data table");
		btnFindGap = new JButton("find track gap");
		JButton btnSaveTracks = new JButton("save tracked cells");
		buttonPanel.add(btnRefresh);
		buttonPanel.add(btnManualTrack);
		//buttonPanel.add(btnEndTrack);
		buttonPanel.add(btnAutoTrack);
		buttonPanel.add(btnCfgAuto);
		//buttonPanel.add(btnEditTrack);
		//buttonPanel.add(btnDeleteTrack);
		buttonPanel.add(btnChangeRoiShape);
		buttonPanel.add(btnDelBefore);
		buttonPanel.add(btnDelAfter);
		
		buttonPanel.add(btnViewPlot);
		buttonPanel.add(btnViewTable);
		buttonPanel.add(btnFindGap);
		//buttonPanel.add(btnSaveTracks);
		buttonPanel.setBorder(border);
		buttonPanel.setBackground(panelColor);
		buttonPanel.setMaximumSize(panelMax);
		contentPanel.add(buttonPanel);
		buttonPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		// configure the JTextArea to display source image info
		//sourceInfo.setBackground(textAreaColor);
		
		sourceInfo.setText(getImageInfo(sourceImage));
		if (sourceImage==null) {
			sourceInfo.setFont(errorFont);
			sourceInfo.setForeground(errorFontColor);
		}
		// configure refresh button
		btnRefresh.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) {refreshSource();}
		});
		// configure  button
		btnManualTrack.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) {
			  doManualTrack = !doManualTrack;
			  btnManualTrack.setText(doManualTrack?"stop manual tracking":"start manual tracking");
			  manualTrack();
		  }
		});
		// configure  button
		/*
		btnEndTrack.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) {finishTrack();}
		});
		*/
		// configure  button
		btnAutoTrack.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) {
			  doAutoTrack = !doAutoTrack;
			  btnAutoTrack.setText(doAutoTrack?"stop auto tracking":"start auto tracking");
			  if (doAutoTrack) new autoTrackThread().start();
		  }
		});
		// configure  button
		btnCfgAuto.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { configureAutoTracking();}
		});
		// configure  button
		btnChangeRoiShape.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { changeRoiShape(); }
		});
		// configure  button
		btnDelBefore.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { deleteAll(false); }
		});
		// configure  button
		btnDelAfter.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { deleteAll(true); }
		});
		// configure  button
		btnViewPlot.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { generatePlot(); }
		});
		// configure  button
		btnViewTable.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { generateTable(); }
		});	
		btnFindGap.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) { findTrackGap(); }
		});	
		// configure  button
		btnSaveTracks.addActionListener(new ActionListener() { 
		  @Override 
		  public void actionPerformed(ActionEvent ae) {}
		});
		
		// add title and content panel to the parent panel, and finally add to plugin frame
		parentPanel.add(contentPanel);
		contentPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
		
		//f.add(parentPanel, BorderLayout.NORTH);
		f.add(parentPanel);
		f.pack();
		//f.showDialog();
	}
	
/*
 * Function groups to manage the source image	
 */
	public void refreshSource() {
		ImagePlus activeImage = WindowManager.getCurrentImage();
	  	if (WindowManager.getImageCount()==0) {
	  		sourceImage=null;
	  		sourceInfo.setText(getImageInfo(null));
	  		sourceInfo.setFont(errorFont);
	  		sourceInfo.setForeground(errorFontColor);
	  		return;
	  	}
	  	if (activeImage!=null) {
	  		sourceImage = activeImage;
	  		prepareSourceImage();
	  	}
	  	sourceInfo.setText(getImageInfo(sourceImage));
	  	if (sourceImage==null) {
	  		sourceInfo.setFont(errorFont);
	  		sourceInfo.setForeground(errorFontColor);
	  	} else {
	  		sourceInfo.setFont(textFont);
	  		sourceInfo.setForeground(fontColor);
	  	}
	}
	
	// deprecated function
	public void makeNewTrack() {
		if (sourceImage==null) return;
		if (currentTrack.size()!=0 && !trackID.contains(currentTrackID)) {
			String title = "Unfinished Track";
			String message = "Current track is not finished."
					+"\n Press Finish to finish the current tracing."
					+"\n Or press Continue to start a new tracing anyway."
					+"\n In case undecided, press Cancel.";
			String yesLabel = "Finish current";
			String noLabel = "Continue";
			YesNoCancelDialog gd = new YesNoCancelDialog(pf, title, message, yesLabel, noLabel);
			if (gd.yesPressed()) {
				finishTrack();
			} else if (gd.cancelPressed()) {
				return;
			} else {} // user confirmed don't (finish) save the current track
		}
		currentTrackID++;
		currentTrack = new ArrayList<Roi>();
		sourceImage.deleteRoi();
		IJ.setTool("point");
		newManualPoint = new PointRoi();
		startFrame = sourceImage.getT();
		currentFrame = startFrame;
		/* create ROI listener
		 *  1: created, 2: moved, 3: modified, 4: extended, 5: completed, 6: deleted
		 */
		listener = new RoiListener() {
			@Override
			public void roiModified(ImagePlus imp, int id) {
				if (imp==sourceImage && id==1) {
					// we captured a ROI creation event on the source image
					/*
					 * ROI getTPosition() doesn't work!!!
					 * Roi currentRoi = sourceImage.getRoi();
					 * int roiT = currentRoi.getTPosition();
					 */
					
					int roiT = sourceImage.getT();
					//IJ.log("startFrame : " + String.valueOf(startFrame));
					//IJ.log("currentFrame: " + String.valueOf(currentFrame));
					//IJ.log("roiT: " + String.valueOf(roiT));
					newManualPoint = (PointRoi) sourceImage.getRoi();
					if (roiT == startFrame) { // a new ROI on t0
						
						currentTrack.add(newManualPoint);
						sourceImage.setT(++currentFrame);
						//sourceImage.deleteRoi();
					} else if (roiT == numT) { // a new ROI reach the end of time
						if (currentFrame!=numT)
							currentTrack.add(newManualPoint);
						else
							currentTrack.set(currentTrack.size()-1, newManualPoint);
						//sourceImage.deleteRoi();
					} else if (roiT < startFrame) { // new ROI before t0
						sourceImage.setT(currentFrame);
						//sourceImage.deleteRoi();
					} else if (roiT == currentFrame) { // new ROI on t
						currentTrack.set(currentTrack.size()-1, newManualPoint);
						sourceImage.setT(++currentFrame);
						//sourceImage.deleteRoi();
					} else if (roiT >= (currentFrame+1)) { // a new ROI on t+N
						currentTrack.add(newManualPoint);
						sourceImage.setT(roiT+1);
						currentFrame = roiT+1;
						//sourceImage.deleteRoi();
					}
				}
			}
		};
		Roi.addRoiListener(listener);
	}
	// deprecated function
	public void finishTrack() {
		Roi.removeRoiListener(listener);
		if (currentTrack.size()==0) return;
		tracks.add(currentTrack);
		trackID.add(currentTrackID);
		trackSize.add(currentTrack.size());
	}
	
	public void manualTrack() {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null) rm = new RoiManager();	
		if (!doManualTrack && listener!=null) {
			Roi.removeRoiListener(listener);
			rm.select(sourceImage, rm.getCount()-1);
			return;
		}
		if (sourceImage==null) return;
		if (rm.getCount()!=0 && rm.getRoi(rm.getCount()-1).equals(sourceImage.getRoi())) 
			sourceImage.setT(sourceImage.getT()+1);
		sourceImage.deleteRoi();
		IJ.setTool("point");
		newManualPoint = new PointRoi();
		startFrame = sourceImage.getT();
		currentFrame = startFrame;
		/* create ROI listener
		 *  1: created, 2: moved, 3: modified, 4: extended, 5: completed, 6: deleted
		 */
		listener = new RoiListener() {
			@Override
			public void roiModified(ImagePlus imp, int id) {
				if (imp==sourceImage && id==1) {
					int currentFrame = imp.getT();
					Roi newRoi = imp.getRoi();
					double xCenter = newRoi.getXBase();
					double yCenter = newRoi.getYBase();
					PointRoi point = new PointRoi(xCenter, yCenter, "medium yellow hybrid");
					point.setPosition(0, imp.getZ(), imp.getT());
					RoiManager.getInstance().add(sourceImage, point, currentFrame);
					if (currentFrame<(numT-1))
						sourceImage.setT(currentFrame+1);
					imp.deleteRoi();
				}
			}
		};
		Roi.addRoiListener(listener);
	}
	
	public void configureAutoTracking() {
		if (sourceImage==null) return;
		String[] channels = new String[numC];
		for (int c=0; c<numC; c++) {
			channels[c] = String.valueOf(c+1);
		}
		
		DefaultPrefService prefs = new DefaultPrefService();
		autoTrackChannel = prefs.getInt(Integer.class, "FucciMCT-autoTrackChannel", autoTrackChannel);
		autoMethod = prefs.getInt(Integer.class, "FucciMCT-autoMethod", autoMethod);
		cropSize = prefs.getDouble(Double.class, "FucciMCT-cropSize", cropSize);
		autoTrackDisplayRate = prefs.getLong(Long.class, "FucciMCT-autoTrackDisplayRate", autoTrackDisplayRate);

		GenericDialog gd = new GenericDialog("Auto Tracking Parameters");
		gd.addChoice("tracking channel:", channels, channels[autoTrackChannel-1]);
		gd.addChoice("Thresholding method:", thresholdMethods, thresholdMethods[autoMethod]);
		gd.addSlider("searching range (small-large)", 5.0d, 50.0d, cropSize, 5.0d);
		gd.addSlider("refresh rate (fast-slow)", 0, 1000, autoTrackDisplayRate, 20);
		gd.showDialog();
		if (gd.wasCanceled()) return;
		autoTrackChannel = gd.getNextChoiceIndex()+1;
		autoMethod = gd.getNextChoiceIndex();
		cropSize = gd.getNextNumber();
		autoTrackDisplayRate = (long) gd.getNextNumber();
		
		prefs.put(Integer.class, "FucciMCT-autoTrackChannel", autoTrackChannel);
		prefs.put(Integer.class, "FucciMCT-autoMethod", autoMethod);
		prefs.put(Double.class, "FucciMCT-cropSize", cropSize);
		prefs.put(Long.class, "FucciMCT-autoTrackDisplayRate", autoTrackDisplayRate);
	}
	
	public void autoTrack() throws InterruptedException {
		Roi.removeRoiListener(listener);
		doManualTrack = false;
		btnManualTrack.setText("start manual tracking");
		RoiManager rm = RoiManager.getInstance();
		if (rm==null) rm = new RoiManager();		
		
		Roi initRoi = sourceImage.getRoi();
		if (initRoi==null) {
			initRoi = rm.getRoi(rm.getCount()-1);
		}

		if (sourceImage==null 
				|| sourceImage.getT()==numT
				|| initRoi==null 
				) {
			doAutoTrack = false;
			btnAutoTrack.setText("start auto tracking");
			return;
		}
		int currentFrame = sourceImage.getT();
		double pixelSize = sourceImage.getCalibration().pixelWidth;
		double xCenter = initRoi.getXBase();
		double yCenter = initRoi.getYBase();
		
		double newX = xCenter; 
		double newY = yCenter;

		// crop around the initial ROI, generate a principle object ROI
		sourceImage.setRoi(new Roi(xCenter-cropSize, yCenter-cropSize, cropSize*2, cropSize*2), false);
		ImagePlus impCrop = new Duplicator().run(sourceImage, autoTrackChannel, autoTrackChannel, sourceImage.getZ(), sourceImage.getZ(), sourceImage.getT(), sourceImage.getT());
		Roi cellRoi = getPrincipleObjRoi(impCrop);
		cellRoi.setLocation(xCenter-cellRoi.getFloatWidth()/2, yCenter-cellRoi.getFloatHeight()/2);
		double[] center = cellRoi.getContourCentroid();
		PointRoi point = new PointRoi(center[0], center[1], "medium yellow hybrid");
		sourceImage.setRoi(cellRoi, true); //impOld.setRoi(cellRoi, true);

		ArrayList<Roi> autoTrackRois = new ArrayList<Roi>();	
		try {
			while (doAutoTrack && currentFrame<numT) {
				
				xCenter = newX; yCenter = newY;
				sourceImage.setT(++currentFrame);
				
				Roi roiCrop = cropRoi(cellRoi);
				sourceImage.setRoi(roiCrop, true);
				impCrop = new Duplicator().run(sourceImage, autoTrackChannel, autoTrackChannel, sourceImage.getZ(), sourceImage.getZ(), sourceImage.getT(), sourceImage.getT());
				cellRoi = getPrincipleObjRoi(impCrop);
				cellRoi.setLocation(roiCrop.getXBase()+cellRoi.getXBase(), roiCrop.getYBase()+cellRoi.getYBase());
				cellRoi.setStrokeColor(Color.YELLOW);
				sourceImage.setRoi(cellRoi, true);
				
				try {Thread.sleep(autoTrackDisplayRate);}
				catch(InterruptedException e){System.out.println(e);}  
				
				center = cellRoi.getContourCentroid();
				PointRoi newPoint = new PointRoi(center[0], center[1], "medium yellow hybrid");
				newPoint.setPosition(0, 1, (int)(currentFrame));

				rm.add(sourceImage, newPoint, currentFrame);
				if (rm.getRoi(rm.getCount()-1).getName().endsWith("0000")) {
					rm.select(rm.getCount()-1);
					rm.runCommand("Delete");
					break;
				}
			}
		} catch (IllegalArgumentException e) {
			doAutoTrack = false;
			btnAutoTrack.setText("start auto tracking");
			rm.select(rm.getCount()-1);
			return;
		}
		rm.select(rm.getCount()-1);
	}
	
	// function to change all ROI type (shape) in the RoiManager
	public void changeRoiShape() {
		if (sourceImage==null) return;
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()==0) return;
		
		
		
		int nROI = rm.getCount();
		
		DefaultPrefService prefs = new DefaultPrefService();
		changeRoiShapeAll = prefs.getBoolean(Boolean.class, "FucciMCT-changeRoiShapeAll", changeRoiShapeAll);
		roiShape = prefs.getInt(Integer.class, "FucciMCT-roiShape", roiShape);
		ovalRoiRadius = prefs.getDouble(Double.class, "FucciMCT-ovalRoiRadius", ovalRoiRadius);
		GenericDialog gd = new GenericDialog("change ROI shape");
		gd.addCheckbox("apply to all", changeRoiShapeAll);
		gd.addChoice("shape", roiShapes, roiShapes[roiShape]);
		gd.addNumericField("radius", ovalRoiRadius, 1, 3, "pixel");
		gd.showDialog();
		if (gd.wasCanceled()) return;
		changeRoiShapeAll = gd.getNextBoolean();
		roiShape = gd.getNextChoiceIndex();
		ovalRoiRadius = gd.getNextNumber();
		prefs.put(Boolean.class, "FucciMCT-changeRoiShapeAll", changeRoiShapeAll);
		prefs.put(Integer.class, "FucciMCT-roiShape", roiShape);
		prefs.put(Double.class, "FucciMCT-ovalRoiRadius", ovalRoiRadius);
		
		int oriIdx = rm.getSelectedIndex();
		int[] selectedIdx;
		if (!changeRoiShapeAll) {
			if (oriIdx==-1) return;
			selectedIdx = new int[]{oriIdx};
		} else {
			selectedIdx = new int[nROI];
			for (int i=0; i<nROI; i++)
				selectedIdx[i] = i;
		}
		if (changeRoiShapeAll && roiShape==2) {
			double start = System.currentTimeMillis();
			Roi timingRoi = getPrincipleObjRoi(sourceImage, autoTrackChannel, rm.getRoi(0), 3*ovalRoiRadius);
			double duration = System.currentTimeMillis() - start;
			double totalDuration = duration * nROI / 1000;
			if (totalDuration>60) {
				String title = "Cell Outline Generation";
				String message = "It will take roughly " + String.valueOf((double)(Math.round(totalDuration/6))/10)
					+ " minutes to generate all cell ROI.\n Press Cancel to abort.";
				if (!IJ.showMessageWithCancel(title, message))
					return;
			}
		}
		sourceImage.deleteRoi();
		rm.runCommand(sourceImage, "Show None");
		for (int idx : selectedIdx) {
			Roi oldRoi = rm.getRoi(idx);
			double xCenter = oldRoi.getXBase() + oldRoi.getFloatWidth()/2;
			double yCenter = oldRoi.getYBase() + oldRoi.getFloatHeight()/2;
			Roi newRoi = null;
			switch (roiShape) {
			case 0:
				newRoi = new PointRoi(xCenter, yCenter, "medium yellow hybrid");
				break;
			case 1:
				newRoi = new OvalRoi(xCenter-ovalRoiRadius, yCenter-ovalRoiRadius, 2*ovalRoiRadius, 2*ovalRoiRadius);
				break;
			case 2:
				if (oldRoi.getType()==2) continue; // skip StarDist ROI
				newRoi = getPrincipleObjRoi(sourceImage, autoTrackChannel, oldRoi, 2*ovalRoiRadius);
				break;
			}
			newRoi.setPosition(oldRoi.getCPosition(), oldRoi.getZPosition(), oldRoi.getTPosition());
			rm.setRoi(newRoi, idx);
		}
		rm.runCommand("Show All");
		if (oriIdx!=-1) rm.select(sourceImage, oriIdx);
		else sourceImage.deleteRoi();
	}
	
	// function to sort ROI Manager according to frame (forward);
	public void sortRoiManager() {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()<=1) return;
		//Roi[] rois = null;
		Roi[] rois = rm.getRoisAsArray().clone();
		Arrays.sort(rois, new Comparator<Roi>() {
			@Override
			public int compare(Roi r1, Roi r2) {
				return Integer.compare(r1.getTPosition(), r2.getTPosition());
			}
		});
		//rm.reset();
		int nROI = rm.getCount();
		for (int i=0; i<nROI; i++) {
			if (rm.getRoi(i).equals(rois[i])) continue;
			rm.setRoi(rois[i], i);
			rm.rename(i, rois[i].getName());
		}
	}
	// function to detect gaps in the tracked ROIs in ROI Manager
	public void findTrackGap() {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()<=1) return;
		sortRoiManager();
		
		int nROI = rm.getCount();
		Roi[] rois = rm.getRoisAsArray();
		int tStart = rois[0].getTPosition();
		int tEnd = rois[nROI-1].getTPosition();
		boolean[] nFrames = new boolean[tEnd-tStart+1];
		for (int i=0; i<nROI; i++) {
			nFrames[rois[i].getTPosition() - tStart] = true;
		}
		
		String gapString = "";
		boolean now = true; boolean next = true; int tNow = 0;
		for (int i=0; i<nFrames.length-1; i++) {
			now = nFrames[i]; next = nFrames[i+1];
			tNow = tStart+i;
			if (now && !next) { // new gap found
				gapString += String.valueOf(tNow+1);
			} else if (!now && !next) {
				if (!gapString.endsWith("-")) gapString += "-";
			} else if (!now && next) {
				if (!gapString.endsWith(String.valueOf(tNow))) gapString += String.valueOf(tNow);
				gapString += ", ";
			}
		}

		String info = sourceInfo.getText();
		int gapIdx = info.indexOf("Gap");
		if (gapIdx!=-1) info = info.substring(0, gapIdx-1);
		
		String gapInfo = "Gap not found.";
		if (gapString.length()!=0) { // gap found, report error
			gapString = gapString.substring(0, gapString.length()-2);
			gapInfo = "Gap found: " + gapString;
			sourceInfo.setFont(errorFont);
			sourceInfo.setForeground(errorFontColor);
		} else {
			sourceInfo.setFont(textFont);
			sourceInfo.setForeground(fontColor);
		}
		sourceInfo.setText(info + "\n" + gapInfo);
	}
	
	// function to delete all ROIs in ROI Manager before or after the selected entry
	public void deleteAll(Boolean deleteForward) {
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()<=1 || rm.getSelectedIndex()==-1) return;
		int nROI = rm.getCount(); int currentIdx = rm.getSelectedIndex();
		int nDelete = deleteForward ? nROI-currentIdx-1 : currentIdx;
		int[] selectedIndexes = new int[nDelete];
		for (int i=0; i<nDelete; i++) {
			selectedIndexes[i] = deleteForward ? currentIdx+i+1 : i;
		}
		if (nDelete>10000) rm.setVisible(false);
		rm.setSelectedIndexes(selectedIndexes);
		rm.runCommand("Delete");
		if (deleteForward)
			rm.select(0);
		else
			rm.select(rm.getCount()-1);
		rm.setVisible(true);
	}
	
	public void generatePlot() {
		if (sourceImage==null) return;
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()==0) return;
		int nROI = rm.getCount();
		for (int i=0; i<nROI; i++) {
			Roi roi = rm.getRoi(i);
			if (roi.getTypeAsString().equals("Point")) {
				IJ.error("ROI " + String.valueOf(i+1) + " is a point! Abort plot generation.");
				return;
			}
		}
		GeneratePlot.plotWithImgAndRoiManager2(sourceImage);
	}
	
	public void generateTable() {
		if (sourceImage==null) return;
		RoiManager rm = RoiManager.getInstance();
		if (rm==null || rm.getCount()==0) return;
		int nROI = rm.getCount();
		
		ResultsTable rt = new ResultsTable();
		for (int i=0; i<nROI; i++) {
			
			Roi roi = rm.getRoi(i);
			if (roi.getTypeAsString().equals("Point")) {
				IJ.error("ROI " + String.valueOf(i+1) + " is a point! Abort table generation.");
				return;
			}
			
			sourceImage.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
			sourceImage.setRoi(roi, false);
			
			rt.incrementCounter();
			rt.addValue("ROI", rm.getName(i));
			//rm.select(sourceImage, i);
			double area = sourceImage.getStatistics().area;
			double xCor = roi.getXBase() + roi.getFloatWidth()/2;
			double yCor = roi.getYBase() + roi.getFloatHeight()/2;
			double tCor = sourceImage.getT();
			rt.addValue("Area", area);
			rt.addValue("X", xCor);
			rt.addValue("Y", yCor);
			rt.addValue("T", tCor);
			double mean = 0.0;
			double stdDev = 0.0;
			for (int c=0; c<numC; c++) {
				sourceImage.setPositionWithoutUpdate(c+1, roi.getZPosition(), roi.getTPosition());
				sourceImage.setRoi(roi, false);
				mean = sourceImage.getStatistics().mean;
				stdDev = sourceImage.getStatistics().stdDev;
				rt.addValue("C"+String.valueOf(c+1)+" mean" , mean);
				rt.addValue("C"+String.valueOf(c+1)+"stdDev", stdDev);
			}
		}
		
		sourceImage.deleteRoi();
		rt.show("Fucci Manual Tracker Data Table");	
	}
	
	@Override
	public void run(String arg) {
		// TODO Auto-generated method stub
		//prepareSourceImage();
		

		pf = new PlugInFrame("Manual Tracker");
		pf.setLayout(new BoxLayout(pf, BoxLayout.Y_AXIS));
		try {
			addPanelToFrame(pf);
			//refreshSource();
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		pf.pack();
		pf.setSize(320, 550);
		pf.setVisible(true);
		pf.setLocationRelativeTo(null);
		//pf.setResizable(false);
		GUI.center(pf);
		


		/*
		DefaultComboBoxModel model = new DefaultComboBoxModel(subTrackArray);
		JComboBox subTrackList = new JComboBox();
		subTrackList.setModel(model);
		subTrackList.setSelectedIndex(0);
		subTrackList.setMaximumSize(new Dimension(150, 30));
		subTrackList.addActionListener(new ActionListener() {
		  @Override
		  public void actionPerformed(ActionEvent event) {
		    selectedSubTrack = (int) subTrackList.getSelectedItem(); 
		  }
		});
		JComboBox trackList = new JComboBox(trackIDArray);
		trackList.setSelectedIndex(0);
		trackList.setMaximumSize(new Dimension(150, 30));
		trackList.addActionListener(new ActionListener() {
		  @Override
		  public void actionPerformed(ActionEvent event) {
		    //JComboBox<Integer> combo = (JComboBox<Integer>) event.getSource();
		    selectedTrack = (int) trackList.getSelectedItem();
		    subTrackArray = getSubTrackAsArray(tm, selectedTrack);
		    model = new DefaultComboBoxModel(subTrackArray);
		    subTrackList.setModel(model);
		  }
		});
		*/



		
		/*
		char keyPressed = 's';
		KeyListener listener = new KeyListener() {
			@Override
			public void keyPressed(KeyEvent event) {
			  keyPressed = event.getKeyChar();
			  keyAction(keyPressed);
			}
			@Override
			public void keyReleased(KeyEvent event) {
			}
			public void keyTyped(KeyEvent event) {
			}
			private void keyAction(char keyPressed) {
				IJ.log("key pressed: " + keyPressed);
			}
		}*/


	}
	
	/*
	 * 	Functions to configure and display strings to source info panel
	 */
		// function to generate string of image information
		public String getImageInfo (
				ImagePlus imp) {
			if (imp==null)	return ("No image recognized!");
			String imgTitle = " " + wrapString(imp.getTitle(), lineWidth, 1);
			String imgSzie = " size: "
					+ new DecimalFormat(".#").format(imp.getSizeInBytes()/1048576)
					+ "MB (" + String.valueOf(imp.getBitDepth()) + " bit)";
			int[] dim = imp.getDimensions();

			String imgRoi = imp.getRoi()==null?"Image does not have active ROI.":"Image has active ROI.";
			String imgOverlay = imp.getOverlay()==null?"Image does not have overlay.":"Image contains overlay.";
			
			String imgDimension = " X:" + String.valueOf(dim[0])
							  + ", Y:" + String.valueOf(dim[1])
							  + ", Z:" + String.valueOf(dim[3])
							  + ", C:" + String.valueOf(dim[2])
							  + ", T:" + String.valueOf(dim[4]);
			return ("Source Image: " + "\n" + imgTitle + "\n" + imgSzie + "\n" + imgDimension + "\n" + imgRoi + "\n" + imgOverlay);
		}
		// function to wrap string after certain length for display in text area
		public String wrapString(
				String inputLongString,
				int wrapLength,
				int indent
				) {
			String wrappedString = ""; String indentStr = "";
			for (int i=0; i<indent; i++)
				indentStr += " ";
			for (int i=0; i<inputLongString.length(); i++) {
				if (i!=0 && i%lineWidth==0)	wrappedString += ("\n"+indentStr);
				wrappedString += inputLongString.charAt(i);
			}
			return wrappedString;
		}
		
		// auto guess ROI radius
		public double guessRadius (ImagePlus imp, Roi roi) {
			int numC = imp.getNChannels();
			double xCenter = roi.getXBase() + roi.getFloatWidth()/2;
			double yCenter = roi.getYBase() + roi.getFloatHeight()/2;
			double initR = 1.0;
			double radiusChannel = 0;
			for (int c=0; c<numC-1; c++) {
				double contrast = 0.0; double meanStart = 0.0;
				imp.setPositionWithoutUpdate(c+1, roi.getZPosition(), roi.getTPosition());
				for (int i=0; i<10; i++) {
					double radius = initR+(double)i;
					OvalRoi oval = new OvalRoi(xCenter-radius, yCenter-radius, radius*2, radius*2);
					imp.setRoi(oval, false);
					double mean = imp.getStatistics().mean;				
					if (contrast>(mean - meanStart)) {
						contrast = mean - meanStart;
						radiusChannel = Math.max(radiusChannel, radius);
					}
					meanStart = mean;
				}
			}
			return radiusChannel;	
		}
		// get principle object ROI, based on point ROI
		public Roi getPrincipleObjRoi(
				ImagePlus imp, // input large image
				int detectionChannel, // taken from auto tracking setting
				Roi roi,	// point roi
				double size // 2 * radius
				) {
			
			double xCenter = roi.getXBase() + roi.getFloatWidth()/2;
			double yCenter = roi.getYBase() + roi.getFloatHeight()/2;
			//double size = 3*guessRadius(imp, roi);
			double filterSize = size/25;
			imp.setPositionWithoutUpdate(roi.getCPosition(), roi.getZPosition(), roi.getTPosition());
			imp.setRoi(new Roi(xCenter-size, yCenter-size, 2*size, 2*size), false);
			ImagePlus mask = new Duplicator().run(imp, detectionChannel, detectionChannel, imp.getZ(), imp.getZ(), imp.getT(), imp.getT());
			//new StackConverter(mask).convertToRGB();
			IJ.run(mask, "8-bit", "");

			ImageProcessor ip = mask.getProcessor();
			if (!ip.isBinary()) {	// threshold image first
				ip.setAutoThreshold(thresholdMethods[autoMethod], true, ImageProcessor.NO_LUT_UPDATE);		
				ByteProcessor bp = ip.createMask();
				mask = new ImagePlus("mask", bp);
			}

			IJ.run(mask, "Canvas Size...", "width=["+2*(size+filterSize)+"] height=["+2*(size+filterSize)+"] position=Center zero");
			IJ.run(mask, "Median...", "radius=["+filterSize+"]");

			ManyBlobs allBlobs = new ManyBlobs(mask); // Extended ArrayList
			allBlobs.setBackground(0); // 0 for black, 1 for 255
			allBlobs.findConnectedComponents(); // Start the Connected Component Algorithm
			int blobIdx = 0;
			//double area = allBlobs.get(blobIdx).getEnclosedArea();
			if (allBlobs.size()>1) {	// more than 1 objects in mask
				double area = 0.0;
				for (int i=0; i<allBlobs.size(); i++) {
					if (allBlobs.get(i).getEnclosedArea()>area) {
						blobIdx = i;
						area = allBlobs.get(i).getEnclosedArea();
					}
				}
			}
			mask.changes=false; mask.close(); System.gc();
			Polygon p = allBlobs.get(blobIdx).getOuterContour();
		    int n = p.npoints;
		    float[] x = new float[p.npoints];
		    float[] y = new float[p.npoints];   
		    for (int j=0; j<n; j++) {
		        x[j] = p.xpoints[j]+0.5f;
		        y[j] = p.ypoints[j]+0.5f;
		    }
		    Roi blobRoi = new PolygonRoi(x,y,n,Roi.FREEROI);	//!!! The original TRACED_ROI doesn't work with slice overlay manipulations !!!
		    blobRoi.setStrokeColor(Color.YELLOW);
		    blobRoi.setLocation(xCenter-size-filterSize+blobRoi.getXBase(), yCenter-size-filterSize+blobRoi.getYBase());

			return blobRoi;
		}
		
	// get principle object ROI, based on area ROI
	public Roi getPrincipleObjRoi(
			ImagePlus imp // input image
			) {
		int width = imp.getWidth();
		int height = imp.getHeight();
		int filterSize = (int)(Math.sqrt(width*height)/50);
		Roi roi = imp.getRoi(); imp.deleteRoi();
		ImagePlus mask = imp.crop(); imp.setRoi(roi);
		ImageProcessor ip = mask.getProcessor();
		if (!ip.isBinary()) {	// threshold image first
			ip.setAutoThreshold(thresholdMethods[autoMethod], true, ImageProcessor.NO_LUT_UPDATE);		
			ByteProcessor bp = ip.createMask();
			mask = new ImagePlus("mask", bp);
			//mask.show();
		}
		//if (!Prefs.blackBackground)
		//	mask.invertLut();
		//boolean useInvertingLut = Prefs.useInvertingLut;
		//Prefs.useInvertingLut = false;
		width = mask.getWidth(); height = mask.getHeight();
		IJ.run(mask, "Canvas Size...", "width=["+(width+2*filterSize)+"] height=["+(height+2*filterSize)+"] position=Center zero");
		IJ.run(mask, "Median...", "radius=["+filterSize+"]");
		//IJ.run(mask, "Fill Holes", "");
		//mask.show();
		ManyBlobs allBlobs = new ManyBlobs(mask); // Extended ArrayList
		allBlobs.setBackground(0); // 0 for black, 1 for 255
		allBlobs.findConnectedComponents(); // Start the Connected Component Algorithm
		int blobIdx = 0;
		//double area = allBlobs.get(blobIdx).getEnclosedArea();
		if (allBlobs.size()>1) {	// more than 1 objects in mask
			double area = 0.0;
			for (int i=0; i<allBlobs.size(); i++) {
				if (allBlobs.get(i).getEnclosedArea()>area) {
					blobIdx = i;
					area = allBlobs.get(i).getEnclosedArea();
				}
			}
		}
		mask.changes=false; mask.close(); System.gc();
		Polygon p = allBlobs.get(blobIdx).getOuterContour();
	    int n = p.npoints;
	    float[] x = new float[p.npoints];
	    float[] y = new float[p.npoints];   
	    for (int j=0; j<n; j++) {
	        x[j] = p.xpoints[j]+0.5f;
	        y[j] = p.ypoints[j]+0.5f;
	    }
	    Roi blobRoi = new PolygonRoi(x,y,n,Roi.FREEROI);	//!!! The original TRACED_ROI doesn't work with slice overlay manipulations !!!
	    blobRoi.setStrokeColor(Color.YELLOW);
	    blobRoi.setLocation(blobRoi.getXBase()-filterSize, blobRoi.getYBase()-filterSize);

		return blobRoi;
	    //return blobRoi.getContourCentroid();
		//return blobRoi;
	}
	// generate cropping ROI
	public OvalRoi cropRoi (Roi cellRoi) {
		double xCenter = cellRoi.getXBase() + cellRoi.getFloatWidth()/2;
		double yCenter = cellRoi.getYBase() + cellRoi.getFloatHeight()/2;
		double size = 2*Math.sqrt(cellRoi.getFloatWidth()*cellRoi.getFloatHeight());
		OvalRoi crop = new OvalRoi(xCenter-size/2, yCenter-size/2, size, size);
		crop.setStrokeColor(new Color(255, 255, 255, 0));
		return crop;		
	}
	
	public void keyAction(char keyPressed) {
		switch (keyPressed) {
		case 'a':
			IJ.log("do auto tracing");
			break;
		case 's':
			IJ.log("stop auto tracing");
			break;
		case 'm':
			IJ.log("manual tracing");
			break;
			
		}
	}
}
