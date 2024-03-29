/**
 *  Copyright 2019 LinkedIn Corporation. All rights reserved.
 *  Licensed under the BSD 2-Clause License. See the LICENSE file in the project root for license information.
 *  See the NOTICE file in the project root for additional information regarding copyright ownership.
 */
package com.linkedin.datastream.server.dms;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.apache.helix.zookeeper.exception.ZkClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.datastream.common.Datastream;
import com.linkedin.datastream.common.DatastreamAlreadyExistsException;
import com.linkedin.datastream.common.DatastreamException;
import com.linkedin.datastream.common.DatastreamStatus;
import com.linkedin.datastream.common.DatastreamUtils;
import com.linkedin.datastream.common.zk.ZkClient;
import com.linkedin.datastream.server.CachedDatastreamReader;
import com.linkedin.datastream.server.HostTargetAssignment;
import com.linkedin.datastream.server.zk.KeyBuilder;
import com.linkedin.datastream.server.zk.ZkAdapter;

import static com.linkedin.datastream.common.DatastreamMetadataConstants.NUM_TASKS;
import static com.linkedin.datastream.server.Coordinator.PAUSED_INSTANCE;


/**
 * ZooKeeper-backed {@link DatastreamStore}
 */
public class ZookeeperBackedDatastreamStore implements DatastreamStore {

  private static final Logger LOG = LoggerFactory.getLogger(ZookeeperBackedDatastreamStore.class.getName());
  private static final Double ONE_MEBIBYTE = (double) (1024 * 1024);
  private static final Double ZNODE_BLOB_SIZE_LIMIT = ONE_MEBIBYTE;
  private final ZkClient _zkClient;
  private final String _cluster;
  private final CachedDatastreamReader _datastreamCache;

  /**
   * Construct an instance of ZookeeperBackedDatastreamStore
   * @param datastreamCache cache for datastream data
   * @param zkClient ZooKeeper client to use
   * @param cluster Brooklin cluster name
   */
  public ZookeeperBackedDatastreamStore(CachedDatastreamReader datastreamCache, ZkClient zkClient, String cluster) {
    Validate.notNull(datastreamCache);
    Validate.notNull(zkClient);
    Validate.notNull(cluster);

    _datastreamCache = datastreamCache;
    _zkClient = zkClient;
    _cluster = cluster;
  }

  private String getZnodePath(String key) {
    return KeyBuilder.datastream(_cluster, key);
  }

  private String getConnectorTaskPath(String connector, String task) {
    return KeyBuilder.connectorTask(_cluster, connector, task);
  }

  private List<String> getInstances() {
    return _zkClient.getChildren(KeyBuilder.liveInstances(_cluster));
  }

  @Override
  public String getAssignedTaskInstance(String datastream, String task) {
    if (StringUtils.isEmpty(task)) {
      return null;
    }
    Datastream stream = getDatastream(datastream);
    if (stream == null) {
      return null;
    }
    return _zkClient.readData(getConnectorTaskPath(stream.getConnectorName(), task), true);
  }

  @Override
  public Datastream getDatastream(String key) {
    if (key == null) {
      return null;
    }
    String path = getZnodePath(key);
    String json = _zkClient.readData(path, true /* returnNullIfPathNotExists */);
    if (json == null) {
      return null;
    }

    Datastream datastream = DatastreamUtils.fromJSON(json);
    if (datastream == null) {
      return null;
    }
    String numTasksPath = KeyBuilder.datastreamNumTasks(_cluster, key);
    String numTasks = _zkClient.readData(numTasksPath, true /* returnNullIfPathNotExists */);
    if (numTasks != null) {
      Objects.requireNonNull(datastream.getMetadata()).put(NUM_TASKS, numTasks);
    }

    return datastream;
  }

  /**
   * Retrieves all the datastreams in the store. Since there may be many datastreams, it is better
   * to return a Stream and enable further filtering and transformation rather that just a List.
   *
   * The datastream key-set used to make this call is cached, it is possible to get a slightly outdated
   * list of datastreams and not have a stream that was just added. It depends on how long it takes for
   * ZooKeeper to notify the change.
   */
  @Override
  public Stream<String> getAllDatastreams() {
    return _datastreamCache.getAllDatastreamNames().stream().sorted();
  }

  @Override
  public void updateDatastream(String key, Datastream datastream, boolean notifyLeader) throws DatastreamException {
    Datastream oldDatastream = getDatastream(key);
    if (oldDatastream == null) {
      throw new DatastreamException("Datastream does not exists, can not be updated: " + key);
    }

    // As this limit is ZK specific, adding this validation check specifically in ZookeeperBackedDatastreamStore.
    double datastreamBlobSizeInMBs = getBlobSizeInMBs(DatastreamUtils.toJSON(datastream));
    Validate.isTrue(datastreamBlobSizeInMBs <= ZNODE_BLOB_SIZE_LIMIT,
        String.format("Encoded blob size: %.2f exceeded the znode threshold of %.2f. Datastream: %s cannot be updated.",
            datastreamBlobSizeInMBs, ZNODE_BLOB_SIZE_LIMIT, datastream.getName()));

    Objects.requireNonNull(datastream.getMetadata()).remove("numTasks");
    String json = DatastreamUtils.toJSON(datastream);
    _zkClient.writeData(getZnodePath(key), json);
    if (notifyLeader) {
      notifyLeaderOfDataChange();
    }
  }

  @Override
  public void createDatastream(String key, Datastream datastream) {
    Validate.notNull(datastream, "null datastream");
    Validate.notNull(key, "null key for datastream" + datastream);

    // As this limit is ZK specific, adding this validation check specifically in ZookeeperBackedDatastreamStore.
    double datastreamBlobSizeInMBs = getBlobSizeInMBs(DatastreamUtils.toJSON(datastream));
    Validate.isTrue(datastreamBlobSizeInMBs <= ZNODE_BLOB_SIZE_LIMIT,
        String.format("Encoded blob size: %.2f exceeded the znode threshold of %.2f. Datastream: %s cannot be created.",
            datastreamBlobSizeInMBs, ZNODE_BLOB_SIZE_LIMIT, datastream.getName()));

    String path = getZnodePath(key);
    if (_zkClient.exists(path)) {
      String content = _zkClient.ensureReadData(path);
      String errorMessage = String.format("Datastream already exists: path=%s, content=%s", key, content);
      LOG.warn(errorMessage);
      throw new DatastreamAlreadyExistsException(errorMessage);
    }
    _zkClient.ensurePath(path);
    String json = DatastreamUtils.toJSON(datastream);
    _zkClient.writeData(path, json);
  }

  /**
   * update the target assignment info for a particular datastream
   * @param key datastream name of the original datastream to be updated
   * @param datastream content of the updated datastream
   * @param targetAssignment the target partition assignment
   * @param notifyLeader whether to notify leader about the update
   */
  @Override
  public void updatePartitionAssignments(String key, Datastream datastream, HostTargetAssignment targetAssignment,
      boolean notifyLeader)
      throws DatastreamException {
    Validate.notNull(datastream, "null datastream");
    Validate.notNull(key, "null key for datastream" + datastream);
    verifyHostname(targetAssignment.getTargetHost());

    long currentTime = System.currentTimeMillis();
    String datastreamGroupName = DatastreamUtils.getTaskPrefix(datastream);
    String path = KeyBuilder.getTargetAssignmentPath(_cluster, datastream.getConnectorName(), datastreamGroupName);
    _zkClient.ensurePath(path);
    if (_zkClient.exists(path)) {
      String json = targetAssignment.toJson();
      _zkClient.ensurePath(path + '/' + currentTime);
      _zkClient.writeData(path + '/' + currentTime, json);
    }

    if (notifyLeader) {
      try {
        _zkClient.writeData(KeyBuilder.getTargetAssignmentBase(_cluster, datastream.getConnectorName()),
            String.valueOf(System.currentTimeMillis()));
      } catch (Exception e) {
        LOG.warn("Failed to touch the assignment update", e);
        throw new DatastreamException(e);
      }
    }
  }

  private void verifyHostname(String hostname) throws DatastreamException {
    try {
      String path = KeyBuilder.instances(_cluster);
      _zkClient.ensurePath(path);
      List<String> instances = _zkClient.getChildren(path);
      Set<String> hostnames = instances.stream().filter(s -> !s.equals(PAUSED_INSTANCE))
          .map(s -> {
            try {
              return ZkAdapter.parseHostnameFromZkInstance(s);
            } catch (Exception ex) {
              LOG.error("Fails to parse instance: " + s, ex);
              return null;
            }
          }).filter(Objects::nonNull).collect(Collectors.toSet());
      if (!hostnames.contains(hostname)) {
        String msg = "Hostname " + hostname + " is not valid";
        LOG.error(msg);
        throw new DatastreamException(msg);
      }
    } catch (Exception ex) {
      LOG.error("Fail to verify the hostname", ex);

      throw new DatastreamException(ex);
    }
  }

  @Override
  public void deleteDatastream(String key) {
    Validate.notNull(key, "null key");

    Datastream datastream = getDatastream(key);
    if (datastream != null) {
      datastream.setStatus(DatastreamStatus.DELETING);
      String data = DatastreamUtils.toJSON(datastream);
      String path = getZnodePath(key);
      _zkClient.updateDataSerialized(path, old -> data);
      notifyLeaderOfDataChange();
    }
  }

  @Override
  public void deleteDatastreamNumTasks(String key) {
    Validate.notNull(key, "null key");

    String path = KeyBuilder.datastreamNumTasks(_cluster, key);

    if (!_zkClient.exists(path)) {
      LOG.warn("Trying to delete znode of datastream for which numTasks does not exist. Datastream name: " + key);
      return;
    }

    LOG.info("Deleting the zk path {} ", path);
    _zkClient.delete(path);
  }

  @Override
  public void forceCleanupDatastream(String key) {
    String assignmentTokensPath = KeyBuilder.datastreamAssignmentTokens(_cluster, key);

    if (!_zkClient.exists(assignmentTokensPath)) {
      LOG.info("Assignment tokens path clear for datastream {}. Nothing to delete", key);
      return;
    }

    try {
      _zkClient.deleteRecursively(assignmentTokensPath);
    } catch (ZkClientException ex) {
      LOG.error("Failed to cleanup assignment tokens for {}", key, ex);
    }
  }

  private void notifyLeaderOfDataChange() {
    String dmsPath = KeyBuilder.datastreams(_cluster);
    // Update the /dms to notify that coordinator needs to act on a deleted or changed datastream.
    _zkClient.updateDataSerialized(dmsPath, old -> String.valueOf(System.currentTimeMillis()));
  }

  // Gets the size of the encoded blob (default charset UTF) in MBs
  private double getBlobSizeInMBs(String stringEncodedBlob) {
    if (Objects.nonNull(stringEncodedBlob) && !stringEncodedBlob.isEmpty()) {
      return ((double) stringEncodedBlob.getBytes(ZkClient.ZK_STRING_SERIALIZER_CHARSET).length) / ONE_MEBIBYTE;
    }
    return 0;
  }
}
