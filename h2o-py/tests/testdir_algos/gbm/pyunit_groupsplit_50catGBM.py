import sys, os
sys.path.insert(1, "../../../")
import h2o

this_file_dir = os.path.dirname(os.path.realpath(__file__))
h2o_home_dir = this_file_dir + "/../../../../"

def groupsplit_50catGBM(ip,port):
  # Connect to h2o
  h2o.init(ip,port)

  # Training set has only 45 categories cat1 through cat45
  #Log.info("Importing 50_cattest_train.csv data...\n")
  train = h2o.import_frame(path=h2o_home_dir + "smalldata/gbm_test/50_cattest_train.csv")
  train["y"] = train["y"].asfactor()

  #Log.info("Summary of 50_cattest_train.csv from H2O:\n")
  #train.summary()
  
  # Train H2O GBM Model:
  #Log.info(paste("H2O GBM with parameters:\nntrees = 10, max_depth = 20, nbins = 20\n", sep = ""))
  model = h2o.gbm(x=train[["x1","x2"]], y=train["y"], ntrees=10, max_depth=5, nbins=20)
  model.show()
 
  # Test dataset has all 50 categories cat1 through cat50
  #Log.info("Importing 50_cattest_test.csv data...\n")
  test = h2o.import_frame(path=h2o_home_dir + "smalldata/gbm_test/50_cattest_test.csv")
  #Log.info("Summary of 50_cattest_test.csv from H2O:\n")
  #test.summary()
  
  # Predict on test dataset with GBM model:
  #Log.info("Performing predictions on test dataset...\n")
  predictions = model.predict(test)
  predictions.show()
  
  # Get the confusion matrix and AUC
  #Log.info("Confusion matrix of predictions (max accuracy):\n")
  performance = model.model_performance(test)
  test_cm = performance._metric_json['confusion_matrices']
  test_auc = performance._auc_data.AUC

if __name__ == "__main__":
  h2o.run_test(sys.argv, groupsplit_50catGBM)