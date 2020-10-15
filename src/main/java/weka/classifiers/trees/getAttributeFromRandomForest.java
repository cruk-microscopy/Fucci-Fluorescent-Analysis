package weka.classifiers.trees;

import weka.classifiers.trees.RandomForest;
import weka.classifiers.meta.getAttributeFromBagging;
import weka.core.Instances;

public class getAttributeFromRandomForest {
	
	public static Instances getAttributeFromRandomForest(RandomForest rf) throws Exception { 
		//RandomForest rf = new RandomForest(); 
	    //Instances data = new Instances(new FileReader("/Users/eibe/datasets/UCI/diabetes.arff")); 
	    //data.setClassIndex(data.numAttributes() - 1); 
	    //j48.buildClassifier(data); 
		return getAttributeFromBagging.getAttributeFromBagging(rf);
	    //new Test2(j48.m_root); 
	  } 
	  
	  public static void main(String[] args) { 
	    
	    try { 
	    	getAttributeFromRandomForest test = new getAttributeFromRandomForest(); 
	    } catch (Exception e) { 
	      e.printStackTrace(); 
	    } 
	  } 

}
