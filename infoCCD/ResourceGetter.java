
/* ResourceGetter... robustly get resources in different bundle types */

/*  <InfoCCD - copyright notice, help viewer, parameter loader, console utilities for any Java program>
    Copyright (C) <2008>  <Jonathan B. Alberts>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package infoCCD;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

public class ResourceGetter { 

	
	// **** For Bundling Apps and EXEs *****
	static int runType;
	static String resourceLocStr;
	static boolean resourceLocSet = false;
	public ClassLoader cl;
	
	public ResourceGetter (int runTypeIn) {
		runType = runTypeIn;
		if (!resourceLocSet) {
			setResourceLocStr();
			resourceLocSet = true;
		}
		cl = this.getClass().getClassLoader();
	}
	
	public URL getResource (String name) {
		// first check if someone has sent a fully constructed URL, for some reason
		if ((name.startsWith("http:")) | (name.startsWith("file:"))) {
			try { return new URL(name); } catch (MalformedURLException mfue) { System.out.println ("Malformed URL Exception in ResourceGetter for" + name); return null; }
		}
		
		if (runType == Info.JUSTJAVA) {
			try {
				//System.out.println(cl.getResource(name).toString());
				return cl.getResource(name); 
			} catch (NullPointerException npe) { 
				System.out.println("Oops! Null pointer exception trying to get " + name);
			}
		} else {
			String urlString = "null";
			try {
				urlString = "file:" + resourceLocStr + name;
				return new URL(urlString);
			} catch (MalformedURLException mfue) { System.out.println ("Malformed URL Exception in ResourceGetter for" + urlString); }
		}
		return null;
		
	}
	
	public static void setResourceLocStr () {
		switch (runType) {
		case Info.JUSTJAVA:
			resourceLocStr = System.getProperty("user.dir") + File.separatorChar;
			break;
		case Info.FROMAPP:
			resourceLocStr = System.getProperty("user.dir") + File.separatorChar + Info.appName + ".app" + File.separatorChar + "Contents" + File.separatorChar + "Resources" + File.separatorChar;
			break;
		case Info.FROMEXE:	// specific to use of Jexepack software
			String tempStr = System.getProperty("jexepack.resdir");
			try {
				File tempFile = new File(tempStr);
				resourceLocStr = tempFile.getCanonicalPath() + File.separatorChar;
				break;
			} catch (IOException ioe) {
				System.out.println("io exception seting resourceLocStr in ResourceGetter.java");
				break;
			}
		}
		//System.out.println ("resourceLocStr set to " + resourceLocStr);
	}
}

