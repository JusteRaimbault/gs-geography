/*
 * Copyright 2006 - 2012 
 *     Julien Baudry	<julien.baudry@graphstream-project.org>
 *     Antoine Dutot	<antoine.dutot@graphstream-project.org>
 *     Yoann Pigné		<yoann.pigne@graphstream-project.org>
 *     Guilhelm Savin	<guilhelm.savin@graphstream-project.org>
 * 
 * This file is part of GraphStream <http://graphstream-project.org>.
 * 
 * GraphStream is a library whose purpose is to handle static or dynamic
 * graph, create them from scratch, file or any source and display them.
 * 
 * This program is free software distributed under the terms of two licenses, the
 * CeCILL-C license that fits European law, and the GNU Lesser General Public
 * License. You can  use, modify and/ or redistribute the software under the terms
 * of the CeCILL-C license as circulated by CEA, CNRS and INRIA at the following
 * URL <http://www.cecill.info> or under the terms of the GNU LGPL as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL-C and LGPL licenses and that you accept their terms.
 */

package org.graphstream.geography.shp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;

import org.graphstream.geography.AttributeFilter;
import org.graphstream.geography.ElementDescriptor;
import org.graphstream.geography.Element;
import org.graphstream.geography.FileDescriptor;
import org.graphstream.geography.Line;
import org.graphstream.geography.Point;

/**
 * This geographical source implementation produces a road network from Navteq
 * shapefiles and takes care of the Z-index conflicts.
 * 
 * @author Merwan Achibet
 */
public class GeoSourceNavteq extends GeoSourceSHP {

	/**
	 * The descriptor matching geographic objects with Z-index points.
	 */
	protected ElementDescriptor zDescriptor;

	protected AttributeFilter zAttributeFilter;
	
	/**
	 * The descriptor matching geographic objects with representations of roads.
	 */
	protected ElementDescriptor roadDescriptor;

	protected AttributeFilter roadAttributeFilter;
	
	/**
	 * A record of nodes already added to the output graph.
	 */
	protected ArrayList<String> addedNodeIds;

	/**
	 * Instantiate a new Navteq source producing a road network.
	 * 
	 * @param roadsFileName
	 *            The path to the file containing road data.
	 * @param zFileName
	 *            The path to the file containing Z data.
	 */
	public GeoSourceNavteq(String roadFileName, String zFileName) {

		FileDescriptor zFileDescriptor = new FileDescriptor(zFileName);
		FileDescriptor roadFileDescriptor = new FileDescriptor(roadFileName);

		// First: select and filter the Z-index points.

		// We need to keep the link ID to join the data from the two files and
		// the Z level to handle overpasses/underpasses/tunnels/bridges...

		this.zAttributeFilter = new AttributeFilter(AttributeFilter.Mode.KEEP);

		this.zAttributeFilter.addAttribute("LINK_ID");
		this.zAttributeFilter.addAttribute("Z_LEVEL");

		// We are only interested in intersection points.

		this.zDescriptor = new ElementDescriptorSHP(this, "Z", this.zAttributeFilter);

		this.zDescriptor.sendElementsToSpatialIndex();
		this.zDescriptor.mustHave("INTRSECT", "Y");
		
		zFileDescriptor.addDescriptor(this.zDescriptor);
		
		this.addFileDescriptor(zFileDescriptor);

		// Second: select and filter the road points.

		// We need the link ID to join the two files.

		this.roadAttributeFilter = new AttributeFilter(AttributeFilter.Mode.KEEP);

		this.roadAttributeFilter.addAttribute("LINK_ID");

		// We are only interested in line features..

		this.roadDescriptor = new ElementDescriptorSHP(this, "Z", this.roadAttributeFilter);

		this.roadDescriptor.onlyConsiderLineEndPoints();

		this.roadDescriptor.mustBe(Element.Type.LINE);
		
		roadFileDescriptor.addDescriptor(roadDescriptor);
		
		addFileDescriptor(roadFileDescriptor);
	}

	/**
	 * Give the descriptor matching geographic objects with Z-index points.
	 * 
	 * @return The Z-index point descriptor.
	 */
	public ElementDescriptor getZDescriptor() {

		return this.zDescriptor;
	}
	
	public AttributeFilter getZAttributeFilter() {
		
		return this.zAttributeFilter;
	}

	/**
	 * Give the descriptor matching geographic objects with representations of
	 * roads
	 * 
	 * @return The road descriptor.
	 */
	public ElementDescriptor getRoadDescriptor() {

		return this.roadDescriptor;
	}

	public AttributeFilter getRoadAttributeFilter() {
		
		return this.roadAttributeFilter;
	}
	
	@Override
	public void transform() {

		read();
		
		this.addedNodeIds = new ArrayList<String>();

		ArrayList<Element> allElements = this.elements.getElementsAtEnd();

		for(Element element : allElements) {

			if(!element.isLine())
				continue;

			Line line = (Line)element;

			// Add the two end points to the graph if necessary.

			Point[] ends = line.getEndPoints();

			String idNode1 = addNode(ends[0], line);
			String idNode2 = addNode(ends[1], line);

			// Draw an edge between the two points.

			if(idNode1 != null && idNode2 != null)
				sendEdgeAdded(this.sourceId, line.getId(), idNode1, idNode2, false);

			// Bind the attributes
		}
	}

	/**
	 * Add a node that represents an intersection to the output graph.
	 * 
	 * This is where the Z-level conflicts are resolved. We search for all the
	 * Z-level points at the same position as the road point to add. One of
	 * these points shares its link ID with the road and contains its relative
	 * elevation.
	 * 
	 * @param point
	 *            The intersection point to add.
	 * @param line
	 *            The line containing the intersection.
	 * @return The ID of the point used as a graph node.
	 */
	protected String addNode(Point point, Line line) {

		// Retrieve the Z-level points at the same position as the road point.

		ArrayList<Element> zPoints = this.index.getElementsAt(point.getPosition().x, point.getPosition().y);

		// Among them, find the Z-level point with the same link ID as the road.

		Point lineZPoint = null;

		for(Element zPoint : zPoints)
			if(zPoint.getAttribute("LINK_ID").equals(line.getAttribute("LINK_ID")))
				lineZPoint = (Point)zPoint;

		if(lineZPoint == null)
			return null;

		// Get the Z level of the Z-level point.

		Object zLevel = lineZPoint.getAttribute("Z_LEVEL");

		// Check if a point at the same position and with the same Z level is
		// already in the output graph.

		Point alreadyHerePoint = null;

		for(Element zPoint : zPoints)
			if(zPoint.getAttribute("Z_LEVEL").equals(zLevel) && this.addedNodeIds.contains(zPoint.getId())) {
				alreadyHerePoint = (Point)zPoint;
				break;
			}

		// If no valid node is already in the graph, add one.

		if(alreadyHerePoint == null) {

			alreadyHerePoint = lineZPoint;
			
			sendNodeAdded(this.sourceId, alreadyHerePoint.getId());

			this.addedNodeIds.add(alreadyHerePoint.getId());

			// Place the new node at an appropriate position.

			sendNodeAttributeAdded(this.sourceId, alreadyHerePoint.getId(), "x", alreadyHerePoint.getPosition().x);
			sendNodeAttributeAdded(this.sourceId, alreadyHerePoint.getId(), "y", alreadyHerePoint.getPosition().y);

			// Bind the attributes.

			HashMap<String, Object> attributes = alreadyHerePoint.getAttributes();

			if(attributes != null)
				for(Entry<String, Object> entry : attributes.entrySet())
					sendNodeAttributeAdded(this.sourceId, alreadyHerePoint.getId(), entry.getKey(), entry.getValue());
		}

		return alreadyHerePoint.getId();
	}

}
