/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.query.context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.iotdb.db.engine.modification.Modification;
import org.apache.iotdb.db.engine.modification.ModificationFile;

/**
 * QueryContext contains the shared information with in a query.
 */
public class QueryContext {

  /**
   * The outer key is the path of a ModificationFile, the inner key in the name of a timeseries and
   * the value is the Modifications of a timeseries in this file.
   */
  private Map<String, Map<String, List<Modification>>> filePathModCache = new HashMap<>();
  /**
   * The key is the path of a ModificationFile and the value is all Modifications in this file.
   * We use this field because each call of Modification.getModifications() return a copy of the
   * Modifications, and we do not want it to create multiple copies within a query.
   */
  private Map<String, List<Modification>> fileModCache = new HashMap<>();

  /**
   * Find the modifications of timeseries 'path' in 'modFile'. If they are not in the cache, read
   * them from 'modFile' and put then into the cache.
   * @param modFile
   * @param path
   * @return
   * @throws IOException
   */
  public List<Modification> getPathModifications(ModificationFile modFile, String path)
          throws IOException {

    Map<String, List<Modification>> fileModifications =
            filePathModCache.computeIfAbsent(modFile.getFilePath(), k -> new HashMap<>());
    List<Modification> pathModifications = fileModifications.get(path);

    if (pathModifications == null) {
      List<Modification> allModifications = fileModCache.get(modFile.getFilePath());
      if (allModifications == null) {
        allModifications = (List<Modification>) modFile.getModifications();
        fileModCache.put(modFile.getFilePath(), allModifications);
      }
      pathModifications = new ArrayList<>();
      if (allModifications.size() > 0) {
        List<Modification> finalPathModifications = pathModifications;
        allModifications.forEach(modification -> {
          if (modification.getPath().equals(path)) {
            finalPathModifications.add(modification);
          }
        });
      }
      fileModifications.put(path, pathModifications);
    }

    return pathModifications;
  }
}
