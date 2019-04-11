# Fucci-Fluorescent-Analysis
A Fiji plugin to detect, track, and analyze Fucci cell lines.

Refers to the <help> button for more information when running the plugin.


<html>
 <h2>Fucci cell fluorescent analysis (ImageJ plugin)</h2>
  version: 1.0.4<br>
  date: 2019.01.24<br>
  author: Ziqiang Huang (Ziqiang.Huang@cruk.cam.ac.uk)<br><br>
 <h3>Usage:</h3>
 <&nbsp>Automate cell tracking and fluorescent analysis <br>
  with Fucci cell lines developed in Fanni Gergely's lab at CRUK-CI.<br>
  <&nbsp>(contact: Daphne Huberts / Sarah Carden)<br>
 <br><&nbsp>This script makes use of Trackmate.<br>
  It take an active tiff image stack as input.
  <&nbsp>User will need to specify the tracking parameters:<br>
  target channel, average cell radius and so on.<br>
  <&nbsp>The plugin will use these parameters to build a trackmate model<br>
  and run it on the given image stack. The result will be displayed.<br>
  as a ResultTable named \Fucci cell tracking data table\,<br>
   Detected track-spots will be stored as circle ROI in RoiManager.<br>;
