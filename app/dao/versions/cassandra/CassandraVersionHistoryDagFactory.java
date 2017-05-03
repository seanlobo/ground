/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dao.versions.cassandra;

import com.google.common.base.CaseFormat;
import dao.versions.VersionHistoryDagFactory;
import db.CassandraClient;
import db.DbDataContainer;
import exceptions.GroundException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import models.models.Structure;
import models.versions.GroundType;
import models.versions.Item;
import models.versions.Version;
import models.versions.VersionHistoryDag;
import models.versions.VersionSuccessor;

public class CassandraVersionHistoryDagFactory implements VersionHistoryDagFactory {
  private final CassandraClient dbClient;
  private final CassandraVersionSuccessorFactory versionSuccessorFactory;

  public CassandraVersionHistoryDagFactory(
      CassandraClient dbClient,
      CassandraVersionSuccessorFactory versionSuccessorFactory) {
    this.dbClient = dbClient;
    this.versionSuccessorFactory = versionSuccessorFactory;
  }

  @Override
  public <T extends Version> VersionHistoryDag<T> create(long itemId) throws GroundException {
    return new VersionHistoryDag<T>(itemId, new ArrayList<>());
  }

  /**
   * Retrieve a DAG from the database.
   *
   * @param itemId the id of the item whose dag we are retrieving
   * @param <T> the type of the versions in this dag
   * @return the retrieved DAG
   * @throws GroundException an error retrieving the DAG
   */
  @Override
  public <T extends Version> VersionHistoryDag<T> retrieveFromDatabase(long itemId)
      throws GroundException {
    List<VersionSuccessor<T>> edges;

    try {
      edges = this.versionSuccessorFactory.retrieveFromDatabaseByItemId(itemId);
    } catch (GroundException e) {
      // do nothing, this just means no version have been added yet.
      return new VersionHistoryDag<T>(itemId, new ArrayList<>());
    }
    return new VersionHistoryDag(itemId, edges);
  }

  /**
   * Add an edge to the DAG.
   *
   * @param dag the DAG to update
   * @param parentId the parent's id
   * @param childId the child's id
   * @param itemId the id of the Item whose DAG we're updating
   * @throws GroundException an error adding the edge
   */
  @Override
  public void addEdge(VersionHistoryDag dag, long parentId, long childId, long itemId)
      throws GroundException {
    VersionSuccessor successor = this.versionSuccessorFactory.create(parentId, childId);

    // Adding to the entry with id = successor.getId()
    List<DbDataContainer> newValue = new ArrayList<>(), predicate = new ArrayList<>();
    newValue.add(new DbDataContainer("item_id", GroundType.LONG, itemId));
    predicate.add(new DbDataContainer("id", GroundType.LONG, successor.getId()));

    this.dbClient.update(newValue, predicate, "version_successor");
    dag.addEdge(parentId, childId, successor.getId());
  }

  /**
   * Truncate the DAG to only have a certain number of levels, removing everything before that.
   *
   * @param dag the DAG to truncate
   * @param numLevels the number of levels to keep
   */
  @Override
  public void truncate(VersionHistoryDag dag, int numLevels, Class<? extends Item> itemType)
      throws GroundException {

    int keptLevels = 1;
    List<Long> previousLevel = dag.getLeaves();
    List<Long> lastLevel = new ArrayList<>();
    while (keptLevels <= numLevels) {
      List<Long> currentLevel = new ArrayList<>();

      previousLevel.forEach(id ->
          currentLevel.addAll(dag.getParent(id))
      );

      lastLevel = previousLevel;
      previousLevel = currentLevel;

      keptLevels++;
    }

    List<Long> deleteQueue = new ArrayList<>(new HashSet<>(previousLevel));
    Set<Long> deleted = new HashSet<>();

    List<DbDataContainer> predicates = new ArrayList<>();
    for (long id : lastLevel) {
      this.versionSuccessorFactory.deleteFromDestination(id, dag.getItemId());
      this.addEdge(dag, 0, id, dag.getItemId());
    }


    while (deleteQueue.size() > 0) {
      long id = deleteQueue.get(0);

      if (id != 0) {
        String[] splits = itemType.getName().split("\\.");
        String tableNamePrefix = splits[splits.length - 1];
        tableNamePrefix = CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, tableNamePrefix);

        if (itemType.equals(Structure.class)) {
          predicates.add(new DbDataContainer("id", GroundType.LONG, id));
          List<String> columns = new ArrayList<>();
          columns.add("key_type_map");
          this.dbClient.deleteColumn(predicates, "structure_version", columns);
          predicates.clear();
        }

        if (itemType.getName().toLowerCase().contains("graph")) {
          predicates.add(new DbDataContainer("id", GroundType.LONG, id));
          List<String> columns = new ArrayList<>();
          columns.add("edge_version_set");
          this.dbClient.deleteColumn(predicates, "graph_version", columns);
          predicates.clear();
        }

        predicates.add(new DbDataContainer("id", GroundType.LONG, id));
        this.dbClient.delete(predicates, tableNamePrefix + "_version");

        if (!itemType.equals(Structure.class)) {
          this.dbClient.delete(predicates, "rich_version");
        }

        this.dbClient.delete(predicates, "version");

        predicates.clear();
        predicates.add(new DbDataContainer("rich_version_id", GroundType.LONG, id));
        this.dbClient.delete(predicates, "rich_version_tag");

        this.versionSuccessorFactory.deleteFromDestination(id, dag.getItemId());

        deleted.add(id);

        List<Long> parents = dag.getParent(id);

        parents.forEach(parentId -> {
          if (!deleted.contains(parentId)) {
            deleteQueue.add(parentId);
          }
        });

        predicates.clear();
      }

      deleteQueue.remove(0);
    }
  }
}
