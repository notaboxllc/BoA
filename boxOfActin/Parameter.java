package boxOfActin;

public class Parameter {
	static Parameter [] theParams = new Parameter[512];  // was 256; raised — membrane/branch lamellipodium params pushed past the cap
	static int paramCt = 0;
	static final int BOOLEAN = -1;
	static final int DOUBLE = 0;
	static final int INT = 1;
	static final int CHANGE_VALUE = 11;
	//static final int MECHANISM_ON = 12;
	
	int type;
	int activeType;
	String label;			// permanent reference for this parameter... changing value can affect the ability to load older config files
	String parameterName;
	String units;
	double defaultValue;
	double curValue;
	double lastValue;
	boolean positiveValue = true;		// by default.. only positive values for parameters
	private boolean active = true;
	boolean dependent = false;
	Parameter dependsOn;
	Object syncO = new Object();
	private boolean mutableAtRuntime = false;  // C4: safe to change via setParam mid-run
	private String description = null;          // optional rich "what it does + likely impact" text,
	                                            // authored next to the parameter and surfaced to tooling
	                                            // (the live param panels' info popovers, docs, etc.).
	                                            // Single source of truth — keep it accurate to the code.


	// C4: builder-style setter; returns this so callers can chain in field init
	public Parameter setMutableAtRuntime() {
		this.mutableAtRuntime = true;
		return this;
	}

	public boolean isMutableAtRuntime() {
		return mutableAtRuntime;
	}

	// Builder-style setter for the rich description; chains after setMutableAtRuntime().
	// Convention: plain prose (no markup), state the default and the direction of impact,
	// and note any caveat (e.g. "on -gpu, applies on restart"). Surfaced verbatim to the UI.
	public Parameter setDescription(String d) {
		this.description = d;
		return this;
	}

	public String getDescription() {
		return description;
	}

	/** C4: Returns all Parameters that have been marked mutableAtRuntime. */
	public static java.util.List<Parameter> getAllMutable() {
		java.util.List<Parameter> result = new java.util.ArrayList<>();
		for (int i = 0; i < paramCt; i++) {
			if (theParams[i] != null && theParams[i].mutableAtRuntime)
				result.add(theParams[i]);
		}
		return result;
	}

	public Parameter (String label, String parameterName, double defaultValue, String units) {
		basicInit(label,parameterName,defaultValue,units);
		addParameter(this);
	}
	
	public Parameter (String label, String parameterName, double defaultValue, String units, boolean activeState) {
		basicInit(label,parameterName,defaultValue,units);
		setActive(activeState);
		addParameter(this);
	}
	
	public Parameter (String label, String parameterName, double defaultValue, String units,int type) {
		basicInit(label,parameterName,defaultValue,units);
		this.type = type;
		addParameter(this);
	}
	
	public Parameter (String label, String parameterName, double defaultValue, String units,int type, boolean activeState) {
		basicInit(label,parameterName,defaultValue,units);
		this.type = type;
		setActive(activeState);
		addParameter(this);
	}
	
	public Parameter (String label, String parameterName, double defaultValue, String units,int type,int activeType) {
		basicInit(label,parameterName,defaultValue,units);
		this.type = type;
		this.activeType = activeType;
		addParameter(this);
	}
	
	public Parameter (String label, String parameterName, double defaultValue, String units,int type,int activeType, boolean activeState) {
		basicInit(label,parameterName,defaultValue,units);
		this.type = type;
		this.activeType = activeType;
		setActive(activeState);
		addParameter(this);
	}
	
	public void basicInit (String label, String parameterName, double defaultValue, String units) {
		this.label = label;
		this.parameterName = parameterName;
		this.defaultValue = defaultValue;
		curValue = defaultValue;
		setLastValue(curValue);
		this.units = units;
		this.type = Parameter.DOUBLE;
		this.activeType = Parameter.CHANGE_VALUE;
		setActive(true);
	}
	
	public void setValue (double newValue) {
		if (!dependent) {
			curValue = newValue;
		} else {
			curValue = dependsOn.getValue();
			active = dependsOn.isActive();
		}
	}
	

	public double getValue () {
		if (!dependent) {
			return curValue;
		} else {
			return dependsOn.getValue();
		}
	}
	
	public float getFloatValue () {
		return (float) getValue();
	}
	
	public int getIntValue () {
		return (int) getValue();
	}
	
	public String getStringValue () {
		return String.valueOf(getValue());
	}
	
	public void addToValue (double addVal) {
		if (active) {
			curValue += addVal;
			if ((positiveValue) & (curValue < 0)) {
				curValue = 0;
			}
		}
	}
	
	public void resetToDefault () {
		curValue = defaultValue;
	}
	
	public void resetToLastValue () {
		curValue = lastValue;
	}
	
	public void setLastValue (double newLastVal) {
		lastValue = newLastVal;
	}
	
	public double getLastValue () {
		return lastValue;
	}
	
	public void setName (String name) {
		parameterName = name;
	}
	
	public String getName () {
		return parameterName;
	}
	
	public String getUnits () {
		return units;
	}
	
	public void setPositiveValueOnly (boolean pos) {
		positiveValue = pos;
	}
	
	public void setActive (boolean active) {
		this.active = active;
	}
	
	public boolean isActive () {
		if (!dependent) {
			return active;
		} else {
			return dependsOn.isActive();
		}
	}
	
	public void setDependence (Parameter dependsOn) {
		dependent = true;
		this.dependsOn = dependsOn;
	}
	
	public void setDependence () {
		dependent = false;
		dependsOn = null;
	}
	
	public static void addParameter (Parameter addP) {
		theParams[paramCt] = addP;
		paramCt++;
	}
	
	public String reportParameter () {
		String pStr = label + ":" + getValue();
		return pStr;
	}
	
			
}
