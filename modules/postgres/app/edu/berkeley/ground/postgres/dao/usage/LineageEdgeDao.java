/**
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * <p>
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.berkeley.ground.postgres.dao.usage;

import com.fasterxml.jackson.databind.JsonNode;
import edu.berkeley.ground.common.exception.GroundException;
import edu.berkeley.ground.common.factory.usage.LineageEdgeFactory;
import edu.berkeley.ground.common.model.usage.LineageEdge;
import edu.berkeley.ground.common.utils.IdGenerator;
import edu.berkeley.ground.postgres.dao.version.ItemDao;
import edu.berkeley.ground.postgres.utils.PostgresStatements;
import edu.berkeley.ground.postgres.utils.PostgresUtils;
import play.db.Database;
import play.libs.Json;

import java.util.List;

public class LineageEdgeDao extends ItemDao<LineageEdge> implements LineageEdgeFactory {

  public LineageEdgeDao(Database dbSource, IdGenerator idGenerator) {
    super(dbSource, idGenerator);
  }

  @Override
  public LineageEdge create(LineageEdge lineageEdge) throws GroundException {
    PostgresStatements statements = super.insert(lineageEdge);
    statements.append(String.format("insert into lineage_edge (item_id, source_key, name) values (%d, '%s', '%s')",
      lineageEdge.getId(), lineageEdge.getSourceKey(), lineageEdge.getName()));
    try {
      PostgresUtils.executeSqlList(dbSource, statements);
      return lineageEdge;
    } catch (Exception e) {
      throw new GroundException(e);
    }
  }

  @Override
  public LineageEdge retrieveFromDatabase(String sourceKey) throws GroundException {
    String sql = String.format("select * from lineage_edge where source_key = \'%s\'", sourceKey);
    JsonNode json = Json.parse(PostgresUtils.executeQueryToJson(dbSource, sql));
    return Json.fromJson(json, LineageEdge.class);
  }

  @Override
  public LineageEdge retrieveFromDatabase(long id) throws GroundException {
    String sql = String.format("select * from lineage_edge where id = %d", id);
    JsonNode json = Json.parse(PostgresUtils.executeQueryToJson(dbSource, sql));
    return Json.fromJson(json, LineageEdge.class);
  }

  @Override
  public List<Long> getLeaves(String sourceKey) throws GroundException {
    LineageEdge lineageEdge  = retrieveFromDatabase(sourceKey);
    return super.getLeaves(lineageEdge.getId());
  }

  @Override
  public void truncate(long itemId, int numLevels) throws GroundException {
    super.truncate(itemId, numLevels);
  }

}

