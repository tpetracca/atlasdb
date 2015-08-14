/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlas.jackson;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.google.common.collect.Iterables;
import com.palantir.atlas.api.TableRowSelection;
import com.palantir.atlas.impl.TableMetadataCache;
import com.palantir.atlasdb.keyvalue.api.ColumnSelection;
import com.palantir.atlasdb.table.description.TableMetadata;

public class TableRowSelectionDeserializer extends StdDeserializer<TableRowSelection> {
    private static final long serialVersionUID = 1L;
    private final TableMetadataCache metadataCache;

    protected TableRowSelectionDeserializer(TableMetadataCache metadataCache) {
        super(TableRowSelection.class);
        this.metadataCache = metadataCache;
    }

    @Override
    public TableRowSelection deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException, JsonProcessingException {
        JsonNode node = jp.readValueAsTree();
        String tableName = node.get("table").textValue();
        TableMetadata metadata = metadataCache.getMetadata(tableName);
        Iterable<byte[]> rows = AtlasDeserializers.deserializeRows(metadata.getRowMetadata(), node.get("rows"));
        Iterable<byte[]> columns = AtlasDeserializers.deserializeNamedCols(metadata.getColumns(), node.get("cols"));
        if (Iterables.isEmpty(columns)) {
            return new TableRowSelection(tableName, rows, ColumnSelection.all());
        } else {
            return new TableRowSelection(tableName, rows, ColumnSelection.create(columns));
        }
    }
}
