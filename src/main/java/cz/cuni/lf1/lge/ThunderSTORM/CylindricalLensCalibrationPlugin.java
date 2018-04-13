package cz.cuni.lf1.lge.ThunderSTORM;

import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.PhasorAstigmatismCalibrationEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.UI.AstigmatismCalibrationDialog;
import cz.cuni.lf1.lge.ThunderSTORM.UI.GUI;
import cz.cuni.lf1.lge.ThunderSTORM.calibration.*;
import cz.cuni.lf1.lge.ThunderSTORM.detectors.ui.IDetectorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.AstigmatismCalibrationEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.estimators.ui.IEstimatorUI;
import cz.cuni.lf1.lge.ThunderSTORM.filters.ui.IFilterUI;
import cz.cuni.lf1.lge.ThunderSTORM.thresholding.Thresholder;
import cz.cuni.lf1.lge.ThunderSTORM.util.UI;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.PlugIn;

import javax.swing.*;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class CylindricalLensCalibrationPlugin implements PlugIn {

    DefocusFunction defocusModel;
    IFilterUI selectedFilterUI;
    IDetectorUI selectedDetectorUI;
    IEstimatorUI selectedEstimatorUI;
    PhasorAstigmatismCalibrationEstimatorUI phasorCalibrationEstimatorUI;
    AstigmatismCalibrationEstimatorUI calibrationEstimatorUI;
    String savePath;
    double stageStep;
    double zRangeLimit;//in nm
    double zzeropos;
    ImagePlus imp;
    Roi roi;

    @Override
    public void run(String arg) {
        GUI.setLookAndFeel();
        //
        imp = IJ.getImage();
        if(imp == null) {
            IJ.error("No image open.");
            return;
        }
        if(imp.getImageStackSize() < 2) {
            IJ.error("Requires a stack.");
            return;
        }
        try {
            //load modules
            calibrationEstimatorUI = new AstigmatismCalibrationEstimatorUI();
            phasorCalibrationEstimatorUI = new PhasorAstigmatismCalibrationEstimatorUI();
            List<IFilterUI> filters = ModuleLoader.getUIModules(IFilterUI.class);
            List<IDetectorUI> detectors = ModuleLoader.getUIModules(IDetectorUI.class);
            List<IEstimatorUI> estimators = Arrays.asList(new IEstimatorUI[]{calibrationEstimatorUI, phasorCalibrationEstimatorUI}); 
            List<DefocusFunction> defocusFunctions = ModuleLoader.getUIModules(DefocusFunction.class);
            Thresholder.loadFilters(filters);

            // get user options
            try {
                GUI.setLookAndFeel();
            } catch(Exception e) {
                IJ.handleException(e);
            }
            AstigmatismCalibrationDialog dialog;
            
            dialog = new AstigmatismCalibrationDialog(imp, filters, detectors, estimators, defocusFunctions);
            if(dialog.showAndGetResult() != JOptionPane.OK_OPTION) {
                return;
            }
            selectedFilterUI = dialog.getActiveFilterUI();
            selectedDetectorUI = dialog.getActiveDetectorUI();
            selectedEstimatorUI = dialog.getActiveEstimatorUI();
            savePath = dialog.getSavePath();
            stageStep = dialog.getStageStep();
            zRangeLimit = dialog.getZRangeLimit();
            defocusModel = dialog.getActiveDefocusFunction();
			//zzeropos is  boolean indicator for setting z=0 at intersection of defocus curves, or at middle of calibration stack - KM
            zzeropos = dialog.getZZeroPos();
          
            String activeEstimator = ""+selectedEstimatorUI;
            String subStringGauss = "ui.AstigmatismCalibrationEstimatorUI";
            String subStringPhasor = "ui.PhasorAstigmatismCalibrationEstimatorUI";
            //IJ.log(activeEstimator);
            roi = imp.getRoi() != null ? imp.getRoi() : new Roi(0, 0, imp.getWidth(), imp.getHeight());

            // perform the calibration
			//if Phasor is selected - KM
            if (activeEstimator.toLowerCase().contains(subStringPhasor.toLowerCase())){
            final PhasorAstigmaticCalibrationProcess process = (PhasorAstigmaticCalibrationProcess) PhasorCalibrationProcessFactory.create(
                    new CalibrationConfig(),
                    selectedFilterUI, selectedDetectorUI, phasorCalibrationEstimatorUI,
                    defocusModel, stageStep, zRangeLimit, imp, roi, zzeropos);
            
            try {
                process.runCalibration();
            } catch(NoMoleculesFittedException ex) {
                // if no beads were succesfully fitted, draw localizations anyway
                process.drawOverlay();
                IJ.handleException(ex);
                return;
            }
            process.drawOverlay();
            process.drawSigmaPlots();

            try {
                process.getCalibration(defocusModel).saveToFile(savePath);
            } catch(IOException ex) {
                UI.showAnotherLocationDialog(ex, process.getCalibration(defocusModel));
            }
            } else 
			//If Gauss is selected, do the standard calibration -KM
			{
			//The boolean zzeropos is added to differentiate between zero at intersection or middle of calibration stack -KM
            final AstigmaticCalibrationProcess process = (AstigmaticCalibrationProcess) CalibrationProcessFactory.create(
                    new CalibrationConfig(),
                    selectedFilterUI, selectedDetectorUI, calibrationEstimatorUI,
                    defocusModel, stageStep, zRangeLimit, imp, roi, zzeropos);

            try {
                process.runCalibration();
            } catch(NoMoleculesFittedException ex) {
                // if no beads were succesfully fitted, draw localizations anyway
                process.drawOverlay();
                IJ.handleException(ex);
                return;
            }
            process.drawOverlay();
            process.drawSigmaPlots();

            try {
                process.getCalibration(defocusModel).saveToFile(savePath);
            } catch(IOException ex) {
                UI.showAnotherLocationDialog(ex, process.getCalibration(defocusModel));
            }
			}
        } catch(Exception ex) {
            IJ.handleException(ex);
        }
    }
}
