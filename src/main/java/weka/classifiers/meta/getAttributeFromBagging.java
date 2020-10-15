package weka.classifiers.meta;

import weka.classifiers.meta.Bagging; 
import weka.classifiers.trees.RandomForest;
import weka.core.Instances; 

public class getAttributeFromBagging { 

  public static Instances getAttributeFromBagging(Bagging bg) { 
	    return bg.m_data; 
  } 
}