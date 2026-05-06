package boxOfActin;
/*
 * ParamLine... a JLabel, JTextField, with units and assundry methods to parse the input
 */

import java.awt.event.*;
import javax.swing.*;
import java.text.*;

public class ParamGui implements ActionListener {
	static ParamGui [] theParamGuis = new ParamGui[180];
	static int paramGuiCt = 0;
	static final int ALWAYS_ON = 0;
	static final int CHECKBOX_ON = 1;
	int type = ALWAYS_ON;
	Parameter param;
	JLabel paramNameLabel,unitsLabel;
	JTextField paramField;
	JCheckBox paramNameCkBox;
	String lastText = "";
	String toolTip = "";
	static String booleanString = " ";
	
	
	// formats
	static int textCols = 7;
	static final int EXPONENTIAL_FORMAT = 1;
	static final int TENSNANOS_FORMAT = 2;
	static final int FRACNANOS_FORMAT = 3;
	static final int INTEGER_FORMAT = 4;
	static DecimalFormat expFormat = new DecimalFormat ("0.00E0");
	static DecimalFormat tensNanosFormat = new DecimalFormat ("0.00");
	static DecimalFormat fracNanosFormat = new DecimalFormat ("0.0000");
	static DecimalFormat intFormat = new DecimalFormat ("0");
	DecimalFormat curFormat = fracNanosFormat;

	public ParamGui (Parameter param) {
		this.type = ALWAYS_ON;
		this.param = param;
		if (param.type == Parameter.INT) { curFormat = ParamGui.intFormat; }
		basicInit();
		syncToParameter();
		formatAllComponents();
		//enableFields();
		addParamGui(this);
	}
	
	public ParamGui (Parameter param, int TYPE) {
		this.type = TYPE;
		this.param = param;
		if (param.type == Parameter.INT) { curFormat = ParamGui.intFormat; }
		basicInit();
		syncToParameter();
		formatAllComponents();
		//enableFields();
		addParamGui(this);
	}
	
	public ParamGui (Parameter param, int TYPE, String toolTip) {
		this.type = TYPE;
		this.param = param;
		this.toolTip = toolTip;
		if (param.type == Parameter.INT) { curFormat = ParamGui.intFormat; }
		basicInit();
		syncToParameter();
		formatAllComponents();
		//enableFields();
		addParamGui(this);
	}
	
	public void basicInit () {
		paramNameLabel = new JLabel(param.getName());
		paramNameCkBox = new JCheckBox(param.getName(),param.isActive());
		paramNameCkBox.setToolTipText(toolTip);
		paramNameCkBox.addActionListener(this);
		unitsLabel = new JLabel(param.getUnits());
		paramField = new JTextField(textCols);
	}
	
	private void formatAllComponents () {
		paramNameLabel.setHorizontalAlignment(JLabel.LEFT);
		paramNameLabel.setFont(Env.controlFont);
		paramNameLabel.setForeground(Env.controlForeColor);
		
		paramNameCkBox.setHorizontalAlignment(JLabel.LEFT);
		paramNameCkBox.setFont(Env.controlFont);
		paramNameCkBox.setForeground(Env.controlForeColor);
		paramNameCkBox.setBackground(Env.controlBackColor);
		
		paramField.setFont(Env.controlFont);
		paramField.setHorizontalAlignment(JTextField.CENTER);
		paramField.setBackground(Env.controlBackColor);
		paramField.setForeground(Env.controlForeColor);
		paramField.setBorder(BorderFactory.createEmptyBorder());
		
		unitsLabel.setHorizontalAlignment(JLabel.CENTER);
		unitsLabel.setFont(Env.controlFont);
		unitsLabel.setForeground(Env.controlForeColor);
	}
	
	public void addToPanel (JPanel p) {
		if (type == CHECKBOX_ON) {
			p.add(paramNameCkBox);
		} else {
			p.add(paramNameLabel);
		}
		p.add(paramField);
		p.add(unitsLabel);
	}
	
	/*public void enableFields () {
		param.setActive(paramNameCkBox.isSelected());
		
		switch(param.activeType) {
		case Parameter.CHANGE_VALUE:
			paramField.setEnabled(true);
			break;
		case Parameter.MECHANISM_ON:
			paramField.setEnabled(paramNameCkBox.isSelected());
			break;
		}
	}*/
		
	public void setNameLabel (String newName) {
		paramNameLabel.setText(newName);
	}
	
	public boolean isInEdit () {
		String curText = paramField.getText();
		if (curText.equals(lastText) & (paramNameCkBox.isSelected() == param.isActive())) {
			return false;
		} else {
			return true;
		}
	}
	
	public void syncToParameter () {
		paramNameCkBox.setSelected(param.isActive());
		paramField.setEnabled(param.isActive());
		if (param.type == Parameter.BOOLEAN) { 
			lastText = ParamGui.booleanString;
			paramField.setText(ParamGui.booleanString);
			return;
		} else {
			lastText = curFormat.format(param.getValue());
			paramField.setText(lastText);
		}
		
		
	}
	
	public void setUnitsLabel (String newUnits) {
		unitsLabel.setText(newUnits);
	}
	
	public JLabel getNameLabel () {
		return paramNameLabel;
	}
	
	public JTextField getField () {
		return paramField;
	}
	
	public JLabel getUnitsLabel () {
		return unitsLabel;
	}
	
	public double getValue () {
		return param.getValue();
	}
	
	public void setValue (double newVal) {
		param.setValue(newVal);
	}
	
	public void setValueFromGui () {
		if (param.type == Parameter.BOOLEAN) {
			param.setActive(paramNameCkBox.isSelected());
			syncToParameter();
			return;
		}
		String valString = paramField.getText();
		try {
			Double valD = Double.valueOf(valString);
			double val = valD.doubleValue();
			param.setValue(val);
			param.setLastValue(val);	// set lastValue whenever updating from GUI
		} catch (java.lang.NumberFormatException nfe) {
			System.out.println("Invalid field entry in " + param.getName() + "... the change was ignored");
		}
		param.setActive(paramNameCkBox.isSelected());
		syncToParameter();
	}
	
	public void setToDefaultValue () {
		param.resetToDefault();
		param.setLastValue(param.getValue());
		syncToParameter();
	}
	
	public void setToLastValue () {
		param.resetToLastValue();
		syncToParameter();
	}
	
	
	public void setFieldType (int textType) {
		switch (textType) {
		case EXPONENTIAL_FORMAT:
			curFormat = expFormat;
			break;
		case TENSNANOS_FORMAT:
			curFormat = tensNanosFormat;
			break;
		case FRACNANOS_FORMAT:
			curFormat = fracNanosFormat;
			break;
		case INTEGER_FORMAT:
			curFormat = intFormat;
			break;
		}
		syncToParameter();
	}
	
	public void actionPerformed( ActionEvent event ) {
		//System.out.println("in action performed");
		//enableFields();
	}
	
	
	public static void syncAllToParameters () {
		for (int i=0;i<paramGuiCt;i++) {
			theParamGuis[i].syncToParameter();
		}
	}
	
	public static void addParamGui(ParamGui addP) {
		theParamGuis[paramGuiCt] = addP;
		paramGuiCt++;
	}
	
}
