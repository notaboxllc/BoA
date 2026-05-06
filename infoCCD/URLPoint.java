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

import java.net.URL;

public class URLPoint {
	static URLPoint endGhost = new URLPoint();
	URL url;
	URLPoint lastPoint = null;
	URLPoint nextPoint = null;

	public URLPoint () {
		url = null;
	}
	
	public URLPoint (URL myURL) {
		url = myURL;
		lastPoint = endGhost;
		nextPoint = endGhost;
		
	}
	
	public URLPoint (URL myURL, URLPoint lastPoint) {
		url = myURL;
		setLinks(lastPoint);
	}
	
	public void setLinks (URLPoint lastPoint) {
		this.lastPoint = lastPoint;
		this.nextPoint = endGhost;
		lastPoint.nextPoint = this;
	}
	
}
