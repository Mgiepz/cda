/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package pt.webdetails.cda.settings;

import org.pentaho.reporting.libraries.resourceloader.ResourceKey;
import org.pentaho.reporting.libraries.resourceloader.ResourceKeyCreationException;
import org.pentaho.reporting.libraries.resourceloader.ResourceManager;

/**
 * @deprecated a tad too specific
 * @author pedrovale
 */
public interface IResourceKeyGetter {
  public ResourceKey getResourceKey(String id, ResourceManager resourceManager) throws ResourceKeyCreationException ;
}
