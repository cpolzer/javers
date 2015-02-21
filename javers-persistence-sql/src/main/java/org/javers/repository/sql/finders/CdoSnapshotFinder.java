package org.javers.repository.sql.finders;

import org.javers.common.collections.Optional;
import org.javers.core.commit.CommitId;
import org.javers.core.commit.CommitMetadata;
import org.javers.core.json.JsonConverter;
import org.javers.core.metamodel.object.*;
import org.joda.time.LocalDateTime;
import org.polyjdbc.core.PolyJDBC;
import org.polyjdbc.core.query.Order;
import org.polyjdbc.core.query.SelectQuery;
import org.polyjdbc.core.query.mapper.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

import static org.javers.repository.sql.PolyUtil.queryForLongList;
import static org.javers.repository.sql.PolyUtil.queryForOptionalLong;
import static org.javers.repository.sql.schema.FixedSchemaFactory.*;

public class CdoSnapshotFinder {

    private final PolyJDBC polyJDBC;
    private JsonConverter jsonConverter;

    public CdoSnapshotFinder(PolyJDBC javersPolyJDBC) {
        this.polyJDBC = javersPolyJDBC;
    }

    public Optional<CdoSnapshot> getLatest(GlobalId globalId) {
        PersistentGlobalId persistentGlobalId = findGlobalIdPk(globalId);
        if (!persistentGlobalId.found()){
            return Optional.empty();
        }

        Optional<Long> maxSnapshot = selectMaxSnapshotPrimaryKey(persistentGlobalId);

        if (maxSnapshot.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(getCdoSnapshotsBySnapshotPk(maxSnapshot.get(), maxSnapshot.get(), persistentGlobalId).get(0));
    }

    public List<CdoSnapshot> getStateHistory(GlobalId globalId, int limit) {
        PersistentGlobalId persistentGlobalId = findGlobalIdPk(globalId);
        if (!persistentGlobalId.found()){
            return Collections.emptyList();
        }

        List<Long> latestSnapshots = selectLatestSnapshotPrimaryKeys(persistentGlobalId, limit);

        if (latestSnapshots.isEmpty()){
            return Collections.emptyList();
        }

        long minSnapshotPk = latestSnapshots.get(0);
        long maxSnapshotPk = latestSnapshots.get(latestSnapshots.size()-1);
        return getCdoSnapshotsBySnapshotPk(minSnapshotPk, maxSnapshotPk, persistentGlobalId);
    }

    //TODO dependency injection
    public void setJsonConverter(JsonConverter jsonConverter) {
        this.jsonConverter = jsonConverter;
    }

    private PersistentGlobalId findGlobalIdPk(GlobalId globalId){
        SelectQuery query = polyJDBC.query()
            .select(GLOBAL_ID_PK)
            .from(GLOBAL_ID_TABLE_NAME + " as g INNER JOIN " +
                  CDO_CLASS_TABLE_NAME + " as c ON " + CDO_CLASS_PK + " = " + GLOBAL_ID_CLASS_FK)
            .where("g." + GLOBAL_ID_LOCAL_ID + " = :localId " +
                   "AND c." + CDO_CLASS_QUALIFIED_NAME + " = :qualifiedName ")
            .withArgument("localId", jsonConverter.toJson(globalId.getCdoId()))
            .withArgument("qualifiedName", globalId.getCdoClass().getName());

        Optional<Long> primaryKey = queryForOptionalLong(query, polyJDBC);

        return new PersistentGlobalId(globalId, primaryKey);
    }

    private List<CdoSnapshot> getCdoSnapshotsBySnapshotPk(long minSnapshotPk, long maxSnapshotPk, final PersistentGlobalId globalId){
        SelectQuery query =
            polyJDBC.query()
                    .select(SNAPSHOT_STATE+ ", " +
                            SNAPSHOT_TYPE+ ", " +
                            COMMIT_AUTHOR + ", " +
                            COMMIT_COMMIT_DATE + ", " +
                            COMMIT_COMMIT_ID)
                    .from(SNAPSHOT_TABLE_NAME + " INNER JOIN " +
                          COMMIT_TABLE_NAME + "  ON " + COMMIT_PK + " = " + SNAPSHOT_COMMIT_FK)
                    .where(SNAPSHOT_PK + " between :minSnapshotPk and :maxSnapshotPk AND " +
                            SNAPSHOT_GLOBAL_ID_FK + " = :globalIdPk")
                    .orderBy(SNAPSHOT_PK, Order.DESC)
                    .withArgument("globalIdPk", globalId.primaryKey.get())
                    .withArgument("minSnapshotPk", minSnapshotPk)
                    .withArgument("maxSnapshotPk", maxSnapshotPk);
        return
        polyJDBC.queryRunner().queryList(query, new ObjectMapper<CdoSnapshot>() {
            @Override
            public CdoSnapshot createObject(ResultSet resultSet) throws SQLException {

                String author = resultSet.getString(COMMIT_AUTHOR);
                LocalDateTime commitDate = new LocalDateTime(resultSet.getTimestamp(COMMIT_COMMIT_DATE));
                CommitId commitId = CommitId.valueOf(resultSet.getString(COMMIT_COMMIT_ID));
                CommitMetadata commit = new CommitMetadata(author, commitDate, commitId);

                SnapshotType snapshotType = SnapshotType.valueOf(resultSet.getString(SNAPSHOT_TYPE));
                CdoSnapshotState state =
                        jsonConverter.snapshotStateFromJson(resultSet.getString(SNAPSHOT_STATE), globalId.instance);
                CdoSnapshotBuilder builder = CdoSnapshotBuilder.cdoSnapshot(globalId.instance, commit);
                builder.withType(snapshotType);
                return builder.withState(state).build();
            }
        });
    }

    private List<Long> selectLatestSnapshotPrimaryKeys(PersistentGlobalId globalId, int limit) {
        SelectQuery query = polyJDBC.query()
            .select(SNAPSHOT_PK)
            .from(SNAPSHOT_TABLE_NAME)
                .where(SNAPSHOT_GLOBAL_ID_FK + " = :globalIdPk")
            .withArgument("globalIdPk", globalId.primaryKey.get())
            .orderBy(SNAPSHOT_PK, Order.ASC)
            .limit(limit);

        return queryForLongList(query, polyJDBC);
    }

    private Optional<Long> selectMaxSnapshotPrimaryKey(PersistentGlobalId globalId) {
        SelectQuery query = polyJDBC.query()
            .select("MAX(" + SNAPSHOT_PK + ")")
            .from(SNAPSHOT_TABLE_NAME)
            .where(SNAPSHOT_GLOBAL_ID_FK + " = :globalIdPk")
            .withArgument("globalIdPk", globalId.primaryKey.get());

        Optional<Long> result = queryForOptionalLong(query, polyJDBC);

        if (result.isPresent() && result.get() == 0){
            return Optional.empty();
        }
        return result;
    }
}