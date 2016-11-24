/*
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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.cube.cli;

import java.io.IOException;
import java.util.Set;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.model.DimensionDesc;
import org.apache.kylin.dict.DictionaryManager;
import org.apache.kylin.dict.DistinctColumnValuesProvider;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.DataModelDesc;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.source.ReadableTable;
import org.apache.kylin.source.SourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;

public class DictionaryGeneratorCLI {

    private static final Logger logger = LoggerFactory.getLogger(DictionaryGeneratorCLI.class);

    public static void processSegment(KylinConfig config, String cubeName, String segmentID, DistinctColumnValuesProvider factTableValueProvider) throws IOException {
        CubeInstance cube = CubeManager.getInstance(config).getCube(cubeName);
        CubeSegment segment = cube.getSegmentById(segmentID);

        processSegment(config, segment, factTableValueProvider);
    }

    private static void processSegment(KylinConfig config, CubeSegment cubeSeg, DistinctColumnValuesProvider factTableValueProvider) throws IOException {
        CubeManager cubeMgr = CubeManager.getInstance(config);

        // dictionary
        for (TblColRef col : cubeSeg.getCubeDesc().getAllColumnsNeedDictionaryBuilt()) {
            logger.info("Building dictionary for " + col);
            ReadableTable inpTable = decideInputTable(cubeSeg.getModel(), col, factTableValueProvider);
            cubeMgr.buildDictionary(cubeSeg, col, inpTable);
        }

        // snapshot
        Set<String> toSnapshot = Sets.newHashSet();
        for (DimensionDesc dim : cubeSeg.getCubeDesc().getDimensions()) {
            TableRef table = dim.getTableRef();
            if (cubeSeg.getModel().isLookupTable(table))
                toSnapshot.add(table.getTableIdentity());
        }
        
        for (String tableIdentity : toSnapshot) {
            logger.info("Building snapshot of " + tableIdentity);
            cubeMgr.buildSnapshotTable(cubeSeg, tableIdentity);
        }
    }
    
    private static ReadableTable decideInputTable(DataModelDesc model, TblColRef col, DistinctColumnValuesProvider factTableValueProvider) {
        KylinConfig config = model.getConfig();
        DictionaryManager dictMgr = DictionaryManager.getInstance(config);
        TblColRef srcCol = dictMgr.decideSourceData(model, col);
        String srcTable = srcCol.getTable();
        
        ReadableTable inpTable;
        if (model.isFactTable(srcTable)) {
            inpTable = factTableValueProvider.getDistinctValuesFor(srcCol);
        } else {
            MetadataManager metadataManager = MetadataManager.getInstance(config);
            TableDesc tableDesc = new TableDesc(metadataManager.getTableDesc(srcTable));
            if (TableDesc.TABLE_TYPE_VIRTUAL_VIEW.equalsIgnoreCase(tableDesc.getTableType())) {
                TableDesc materializedTbl = new TableDesc();
                materializedTbl.setDatabase(config.getHiveDatabaseForIntermediateTable());
                materializedTbl.setName(tableDesc.getMaterializedName());
                inpTable = SourceFactory.createReadableTable(materializedTbl);
            } else {
                inpTable = SourceFactory.createReadableTable(tableDesc);
            }
        }

        return inpTable;
    }
}
