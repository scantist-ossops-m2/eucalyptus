/*************************************************************************
 * Copyright 2009-2015 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.blockstorage.async;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.hibernate.Criteria;
import org.hibernate.criterion.Example;
import org.hibernate.criterion.MatchMode;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;

import com.eucalyptus.blockstorage.FileResource;
import com.eucalyptus.blockstorage.LogicalStorageManager;
import com.eucalyptus.blockstorage.S3SnapshotTransfer;
import com.eucalyptus.blockstorage.SnapshotTransfer;
import com.eucalyptus.blockstorage.StorageResource;
import com.eucalyptus.blockstorage.StorageResourceWithCallback;
import com.eucalyptus.blockstorage.entities.SnapshotInfo;
import com.eucalyptus.blockstorage.entities.VolumeInfo;
import com.eucalyptus.blockstorage.util.StorageProperties;
import com.eucalyptus.entities.Entities;
import com.eucalyptus.entities.TransactionResource;
import com.eucalyptus.entities.Transactions;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.metrics.MonitoredAction;
import com.eucalyptus.util.metrics.ThruputMetrics;
import com.google.common.base.Strings;

import edu.ucsb.eucalyptus.util.EucaSemaphore;
import edu.ucsb.eucalyptus.util.EucaSemaphoreDirectory;
import edu.ucsb.eucalyptus.util.SystemUtil;
import edu.ucsb.eucalyptus.util.SystemUtil.CommandOutput;

public class VolumeCreator implements Runnable {
  private static Logger LOG = Logger.getLogger(VolumeCreator.class);
  private static Random randomGenerator = new Random();

  private String volumeId;
  private String snapshotId;
  private String parentVolumeId;
  private int size;
  private LogicalStorageManager blockManager;

  public VolumeCreator(String volumeId, String snapshotSetName, String snapshotId, String parentVolumeId, int size,
      LogicalStorageManager blockManager) {
    this.volumeId = volumeId;
    this.snapshotId = snapshotId;
    this.parentVolumeId = parentVolumeId;
    this.size = size;
    this.blockManager = blockManager;
  }

  @Override
  public void run() {
    boolean success = true;
    if (snapshotId != null) {
      try {
        SnapshotInfo searchFor = new SnapshotInfo(snapshotId);
        searchFor.setStatus(StorageProperties.Status.available.toString()); // search only for available snapshot in the az

        List<SnapshotInfo> foundSnapshotInfos = Transactions.findAll(searchFor);

        if (foundSnapshotInfos == null || foundSnapshotInfos.isEmpty()) {
          // SC *may not* have a database record for the snapshot and or the actual snapshot
          EucaSemaphore semaphore = EucaSemaphoreDirectory.getSolitarySemaphore(snapshotId);
          try {
            semaphore.acquire(); // Get the semaphore to avoid concurrent access by multiple threads
            foundSnapshotInfos = Transactions.findAll(searchFor); // Check if another thread setup the snapshot

            if (foundSnapshotInfos.size() == 0) { // SC does not have a database record for the snapshot
              SnapshotInfo azSnap = null;
              SnapshotInfo sourceSnap = null;

              try (TransactionResource tr = Entities.transactionFor(SnapshotInfo.class)) {
                searchFor.setScName(null);
                searchFor.setIsOrigin(Boolean.TRUE);

                Criteria searchCriteria = Entities.createCriteria(SnapshotInfo.class);
                searchCriteria.setReadOnly(true);
                searchCriteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
                searchCriteria.setCacheable(true);
                searchCriteria.add(Example.create(searchFor).enableLike(MatchMode.EXACT));
                searchCriteria.setMaxResults(1);

                foundSnapshotInfos = (List<SnapshotInfo>) searchCriteria.list();

                if (foundSnapshotInfos == null || foundSnapshotInfos.isEmpty()) { // pre 4.4.0 scneario

                  // Search for the snapshots on other clusters in the ascending order of creation time stamp and get the first one
                  searchFor.setIsOrigin(null);
                  Criteria snapCriteria =
                      Entities.createCriteria(SnapshotInfo.class).setReadOnly(true).setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY)
                          .setCacheable(true).add(Example.create(searchFor).enableLike(MatchMode.EXACT)).addOrder(Order.asc("creationTimestamp"));

                  foundSnapshotInfos = (List<SnapshotInfo>) snapCriteria.list();
                }

                tr.commit();
              }

              if (foundSnapshotInfos != null && foundSnapshotInfos.size() > 0) {
                sourceSnap = foundSnapshotInfos.get(0);
              } else {
                throw new EucalyptusCloudException("No record of snapshot " + snapshotId + " in any availability zone");
              }

              // TODO this might be unnecessary
              // If size was not found in database, bail out. Can't create a snapshot without the size
              if (sourceSnap.getSizeGb() == null || sourceSnap.getSizeGb() <= 0) {
                throw new EucalyptusCloudException(
                    "Snapshot size for " + snapshotId + " is unknown. Cannot prep snapshot holder on the storage backend");
              }

              // Copy base snapshot info to this snapshot
              azSnap = copySnapshotInfo(sourceSnap);

              // Check for the snpahsot on the storage backend. Clusters/zones/partitions may be connected to the same storage backend in
              // which case snapshot does not have to be downloaded from ObjectStorage.
              if (!blockManager.getFromBackend(snapshotId, sourceSnap.getSizeGb())) {
                // Storage backend does not contain snapshot. Download snapshot from OSG
                LOG.debug(snapshotId + " not found on storage backend. Will attempt to download from objectstorage gateway");

                // check whether upload is incremental snapshot
                if (!Strings.isNullOrEmpty(sourceSnap.getPreviousSnapshotId())) {

                  LOG.info(snapshotId + " is an incremental snapshot orignating from az " + sourceSnap.getScName());

                  // check if backend supports incremental snapshot
                  if (blockManager.supportsIncrementalSnapshots()) {

                    List<SnapshotInfo> allDeltas = null;
                    List<SnapshotInfo> nonDeltas = null;
                    SnapshotInfo firstNonDelta = null;

                    try (TransactionResource tr = Entities.transactionFor(SnapshotInfo.class)) {
                      SnapshotInfo searcher = new SnapshotInfo(null);
                      searcher.setVolumeId(sourceSnap.getVolumeId());
                      searcher.setScName(sourceSnap.getScName());
                      searcher.setIsOrigin(Boolean.TRUE);

                      Criteria nonDeltaCriteria = Entities.createCriteria(SnapshotInfo.class);
                      nonDeltaCriteria.add(Example.create(searcher).enableLike(MatchMode.EXACT));
                      nonDeltaCriteria.add(Restrictions.and(StorageProperties.SNAPSHOT_DELTA_RESTORATION_CRITERION,
                          Restrictions.lt("startTime", sourceSnap.getStartTime()), Restrictions.isNull("previousSnapshotId")));
                      nonDeltaCriteria.addOrder(Order.desc("startTime"));
                      nonDeltaCriteria.setReadOnly(true);
                      nonDeltaCriteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
                      nonDeltaCriteria.setMaxResults(1);
                      nonDeltas = (List<SnapshotInfo>) nonDeltaCriteria.list();

                      if (nonDeltas != null && !nonDeltas.isEmpty()) {
                        firstNonDelta = nonDeltas.get(0);
                      } else {
                        tr.commit();
                        LOG.warn("Unable to lookup metadata for last full/non-delta snapshot taken on volume " + sourceSnap.getVolumeId());
                        throw new EucalyptusCloudException(
                            "Unable to lookup metadata for last full/non-delta snapshot taken on volume " + sourceSnap.getVolumeId());
                      }

                      Criteria deltaCriteria = Entities.createCriteria(SnapshotInfo.class);
                      deltaCriteria.add(Example.create(searcher).enableLike(MatchMode.EXACT));
                      deltaCriteria.add(
                          Restrictions.and(StorageProperties.SNAPSHOT_DELTA_RESTORATION_CRITERION, Restrictions.gt("startTime", firstNonDelta.getStartTime()),
                              Restrictions.le("startTime", sourceSnap.getStartTime()), Restrictions.isNotNull("previousSnapshotId")));
                      deltaCriteria.addOrder(Order.asc("startTime"));
                      deltaCriteria.setReadOnly(true);
                      deltaCriteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
                      allDeltas = (List<SnapshotInfo>) deltaCriteria.list();

                      tr.commit();
                    }

                    if (allDeltas == null || allDeltas.isEmpty()) {
                      LOG.warn("Unable to lookup metadata for snapshot deltas taken on volume " + sourceSnap.getVolumeId());
                      throw new EucalyptusCloudException("Unable to lookup metadata for snapshot deltas taken on volume " + sourceSnap.getVolumeId());
                    }

                    // restore base snapshot with first snap
                    downloadAndRestoreBase(firstNonDelta);

                    SnapshotInfo prevSnap = firstNonDelta;

                    // apply deltas
                    for (SnapshotInfo currentSnap : allDeltas) {
                      downloadAndRestoreDelta(currentSnap, prevSnap);
                      prevSnap = currentSnap;
                    }

                    // Cleanup snapshot state and set it up for volume creation
                    blockManager.completeSnapshotRestorationFromDeltas(snapshotId);
                  } else {
                    LOG.warn("Snapshot " + this.snapshotId
                        + " cannot be restored in this availability zone since it does not support incremental snapshots. Failing volume "
                        + this.volumeId);
                    throw new EucalyptusCloudException("Snapshot " + this.snapshotId
                        + " cannot be restored in this availability zone since it does not support incremental snapshots. Failing volume "
                        + this.volumeId);
                  }
                } else {
                  // Not a delta, regular download operation
                  downloadSnapshot(sourceSnap);
                }

                // no metadata changes required beyond copying the info from source

              } else { // Storage backend contains snapshot
                // Just create a record of it for this partition in the DB and get going!
                LOG.debug(snapshotId + " found on storage backend");
                // update the metadata as necessary
                azSnap.setPreviousSnapshotId(sourceSnap.getPreviousSnapshotId());
                azSnap.setSnapPointId(sourceSnap.getSnapPointId());
              }

              // Create a snapshot record for this SC
              Transactions.save(azSnap);
            } else { // SC has a database record for the snapshot
              // This condition is hit when concurrent threads compete to create a volume from a snapshot that did not exist on this SC. One
              // of the concurrent threads may have finished the snapshot prep there by making it available to all other threads
              SnapshotInfo foundSnapshotInfo = foundSnapshotInfos.get(0);
              if (!StorageProperties.Status.available.toString().equals(foundSnapshotInfo.getStatus())) {
                success = false;
                LOG.warn("snapshot " + foundSnapshotInfo.getSnapshotId() + " not available.");
              } else {
                // Do NOT create the volume here as this is synchronized block. Snapshot prepping has to be synchronized, volume
                // creation can be done in parallel
              }
            }
          } catch (InterruptedException ex) {
            throw new EucalyptusCloudException("semaphore could not be acquired");
          } finally {
            try {
              semaphore.release();
            } finally {
              EucaSemaphoreDirectory.removeSemaphore(snapshotId);
            }
          }

          // Create the volume from the snapshot, this can happen in parallel.
          if (success) {
            size = blockManager.createVolume(volumeId, snapshotId, size);
          }
        } else { // SC has a database record for the snapshot
          // Repeated logic, fix it!
          SnapshotInfo foundSnapshotInfo = foundSnapshotInfos.get(0);
          if (!StorageProperties.Status.available.toString().equals(foundSnapshotInfo.getStatus())) {
            success = false;
            LOG.warn("snapshot " + foundSnapshotInfo.getSnapshotId() + " not available.");
          } else {
            size = blockManager.createVolume(volumeId, snapshotId, size);
          }
        }
      } catch (Exception ex) {
        success = false;
        LOG.error("Failed to create volume " + volumeId, ex);
      }
    } else { // Not a snapshot-based volume create.
      try {
        if (parentVolumeId != null) {
          // Clone the parent volume.
          blockManager.cloneVolume(volumeId, parentVolumeId);
        } else {
          // Create a regular empty volume
          blockManager.createVolume(volumeId, size);
        }
      } catch (Exception ex) {
        success = false;
        LOG.error("Failed to create volume " + volumeId, ex);
      }
    }

    // Update database record for the volume.
    VolumeInfo volumeInfo = new VolumeInfo(volumeId);
    try (TransactionResource tr = Entities.transactionFor(VolumeInfo.class)) {
      VolumeInfo foundVolumeInfo = Entities.uniqueResult(volumeInfo);
      if (foundVolumeInfo != null) {
        if (success) {
          foundVolumeInfo.setStatus(StorageProperties.Status.available.toString());
          ThruputMetrics.endOperation(snapshotId != null ? MonitoredAction.CREATE_VOLUME_FROM_SNAPSHOT : MonitoredAction.CREATE_VOLUME, volumeId,
              System.currentTimeMillis());
        } else {
          foundVolumeInfo.setStatus(StorageProperties.Status.failed.toString());
        }
        if (snapshotId != null) {
          foundVolumeInfo.setSize(size);
        }
      } else {
        LOG.error("VolumeInfo entity for volume id " + volumeId + " was not found in the database");
      }
      tr.commit();
    } catch (Exception e) {
      LOG.error("Failed to update VolumeInfo entity for volume id " + volumeId + " in the database", e);
    }
  }

  // DO NOT throw any exceptions from cleaning routines. Log the errors and move on
  private void cleanFailedSnapshot(String snapshotId) {
    if (snapshotId == null)
      return;
    LOG.debug("Disconnecting and cleaning local snapshot after failed snapshot transfer: " + snapshotId);
    try {
      blockManager.finishVolume(snapshotId);
    } catch (Exception e) {
      LOG.error("Error finishing failed snapshot " + snapshotId, e);
    } finally {
      try {
        blockManager.cleanSnapshot(snapshotId);
      } catch (Exception e) {
        LOG.error("Error deleting failed snapshot " + snapshotId, e);
      }
    }
  }

  private SnapshotInfo copySnapshotInfo(SnapshotInfo source) {
    SnapshotInfo copy = new SnapshotInfo(source.getSnapshotId());
    copy.setSizeGb(source.getSizeGb());
    copy.setSnapshotLocation(source.getSnapshotLocation());
    copy.setUserName(source.getUserName());
    copy.setVolumeId(source.getVolumeId());
    copy.setStartTime(source.getStartTime());
    copy.setProgress(source.getProgress());
    copy.setStatus(source.getStatus());
    copy.setIsOrigin(Boolean.FALSE);
    return copy;
  }

  private String downloadSnapshotToTempFile(SnapshotTransfer snapshotTransfer) throws EucalyptusCloudException {

    String tmpUncompressedFileName = null;
    File tmpUncompressedFile = null;
    int retry = 0;
    int maxRetry = 5;

    do {
      tmpUncompressedFileName =
          StorageProperties.storageRootDirectory + File.separator + snapshotId + "-" + String.valueOf(randomGenerator.nextInt());
      tmpUncompressedFile = new File(tmpUncompressedFileName);
    } while (tmpUncompressedFile.exists() && retry++ < maxRetry);

    // This should be *very* rare
    if (retry >= maxRetry) {
      // Nothing to clean up at this point
      throw new EucalyptusCloudException("Could not get a temporary file for snapshot " + snapshotId + " download after " + maxRetry + " attempts");
    }

    // Download the snapshot from OSG
    try {
      snapshotTransfer.download(new FileResource(snapshotId, tmpUncompressedFileName));
    } catch (Exception ex) {
      // Cleanup
      cleanupFile(tmpUncompressedFile);
      throw new EucalyptusCloudException("Failed to download snapshot " + snapshotId + " from objectstorage", ex);
    }

    return tmpUncompressedFileName;
  }

  private void cleanupFile(String fileName) {
    try {
      cleanupFile(new File(fileName));
    } catch (Exception e) {
      LOG.error("Failed to delete file", e);
    }
  }

  private void cleanupFile(File file) {
    if (file != null && file.exists()) {
      try {
        file.delete();
      } catch (Exception e) {
        LOG.error("Failed to delete file", e);
      }
    }
  }

  private void downloadSnapshot(SnapshotInfo sourceSnap) throws Exception {

    String bucket = null;
    String key = null;

    if (StringUtils.isBlank(sourceSnap.getSnapshotLocation())) {
      throw new EucalyptusCloudException(
          "Snapshot location (bucket, key) for " + snapshotId + " is unknown. Cannot download snapshot from objectstorage.");
    }
    String[] names = SnapshotInfo.getSnapshotBucketKeyNames(sourceSnap.getSnapshotLocation());
    bucket = names[0];
    key = names[1];
    if (StringUtils.isBlank(bucket) || StringUtils.isBlank(key)) {
      throw new EucalyptusCloudException(
          "Failed to parse bucket and key information for downloading " + snapshotId + ". Cannot download snapshot from objectstorage.");
    }

    // Try to fetch the snapshot size before preparing the snapshot holder on the backend. If size is unavailable, the snapshot
    // must be downloaded, unzipped and measured before creating the snapshot holder on the backend. Some SANs (Equallogic) add
    // arbitrary amount of writable space to the lun and hence the exact size of the snapshot is required for preparing the
    // holder on the backend
    SnapshotTransfer snapshotTransfer = new S3SnapshotTransfer(snapshotId, bucket, key);
    Long actualSizeInBytes = null;
    try {
      actualSizeInBytes = snapshotTransfer.getSizeInBytes();
    } catch (Exception e) {
      LOG.debug("Snapshot size not found", e);
    }

    if (actualSizeInBytes == null) { // Download the snapshot from OSG and find out the size

      String tmpSnapshotFileName = null;
      try {
        tmpSnapshotFileName = downloadSnapshotToTempFile(snapshotTransfer);

        File snapFile = new File(tmpSnapshotFileName);
        if (!snapFile.exists()) {
          throw new EucalyptusCloudException("Unable to find snapshot " + snapshotId + "on SC");
        }

        // TODO add snapshot size to osg object metadata

        long actualSnapSizeInMB = (long) Math.ceil((double) snapFile.length() / StorageProperties.MB);

        try {
          // Allocates the necessary resources on the backend
          StorageResourceWithCallback srwc = blockManager.prepSnapshotForDownload(snapshotId, sourceSnap.getSizeGb(), actualSnapSizeInMB);

          if (srwc != null && srwc.getSr() != null && srwc.getCallback() != null) {
            StorageResource storageResource = srwc.getSr();

            // Check if the destination is a block device
            if (storageResource.getPath().startsWith("/dev/")) {
              CommandOutput output = SystemUtil.runWithRawOutput(new String[] {StorageProperties.EUCA_ROOT_WRAPPER, "dd", "if=" + tmpSnapshotFileName,
                  "of=" + storageResource.getPath(), "bs=" + StorageProperties.blockSize});
              LOG.debug("Output of dd command: " + output.error);
              if (output.returnValue != 0) {
                throw new EucalyptusCloudException("Failed to copy the snapshot to the right location due to: " + output.error);
              }
              cleanupFile(tmpSnapshotFileName);
            } else {
              // Rename file
              if (!snapFile.renameTo(new File(storageResource.getPath()))) {
                throw new EucalyptusCloudException("Failed to rename the snapshot");
              }
            }

            // Execute the callback to finish the snapshot
            blockManager.executeCallback(srwc.getCallback(), srwc.getSr());
          } else {
            LOG.warn("Block Manager replied that " + snapshotId
                + " not on backend, but snapshot preparation indicated that the snapshot is already present");
          }
        } catch (Exception ex) {
          LOG.error("Failed to prepare the snapshot " + snapshotId + " on storage backend. Cleaning up the snapshot on backend", ex);
          cleanFailedSnapshot(snapshotId);
          throw ex;
        }
      } catch (Exception ex) {
        LOG.error("Failed to prepare the snapshot " + snapshotId + " on the storage backend. Cleaning up the snapshot on SC", ex);
        cleanupFile(tmpSnapshotFileName);
        throw ex;
      }
    } else { // Prepare the snapshot holder and download the snapshot directly to it

      long actualSnapSizeInMB = (long) Math.ceil((double) actualSizeInBytes / StorageProperties.MB);

      try {
        // Allocates the necessary resources on the backend
        StorageResourceWithCallback srwc = blockManager.prepSnapshotForDownload(snapshotId, sourceSnap.getSizeGb(), actualSnapSizeInMB);

        if (srwc != null && srwc.getSr() != null && srwc.getCallback() != null) {
          // Download the snapshot to the destination
          snapshotTransfer.download(srwc.getSr());

          // Execute the callback to finish the snapshot
          blockManager.executeCallback(srwc.getCallback(), srwc.getSr());
        } else {
          LOG.warn("Block Manager replied that " + snapshotId
              + " not on backend, but snapshot preparation indicated that the snapshot is already present");
        }
      } catch (Exception ex) {
        LOG.error("Failed to prepare the snapshot " + snapshotId + " on storage backend. Cleaning up the snapshot on backend", ex);
        cleanFailedSnapshot(snapshotId);
        throw ex;
      }
    }
  }

  private void downloadAndRestoreBase(SnapshotInfo snap) throws Exception {
    String bucket = null;
    String key = null;

    if (StringUtils.isBlank(snap.getSnapshotLocation())) {
      throw new EucalyptusCloudException(
          "Snapshot location (bucket, key) for " + snap.getSnapshotId() + " is unknown. Cannot download snapshot from objectstorage.");
    }
    String[] names = SnapshotInfo.getSnapshotBucketKeyNames(snap.getSnapshotLocation());
    bucket = names[0];
    key = names[1];
    if (StringUtils.isBlank(bucket) || StringUtils.isBlank(key)) {
      throw new EucalyptusCloudException(
          "Failed to parse bucket and key information for downloading " + snap.getSnapshotId() + ". Cannot download snapshot from objectstorage.");
    }

    // Try to fetch the snapshot size before preparing the snapshot holder on the backend. If size is unavailable, the snapshot
    // must be downloaded, unzipped and measured before creating the snapshot holder on the backend. Some SANs (Equallogic) add
    // arbitrary amount of writable space to the lun and hence the exact size of the snapshot is required for preparing the
    // holder on the backend
    SnapshotTransfer snapshotTransfer = new S3SnapshotTransfer(snap.getSnapshotId(), bucket, key);
    Long actualSizeInBytes = null;
    try {
      actualSizeInBytes = snapshotTransfer.getSizeInBytes();
    } catch (Exception e) {
      LOG.debug("Snapshot size not found", e);
    }

    if (actualSizeInBytes != null) { // Prepare the snapshot holder and download the snapshot directly to it
      long actualSnapSizeInMB = (long) Math.ceil((double) actualSizeInBytes / StorageProperties.MB);

      // Allocates the necessary resources on the backend
      StorageResourceWithCallback srwc = blockManager.prepSnapshotBaseForRestore(snapshotId, snap.getSizeGb(), snap.getSnapPointId());

      if (srwc != null && srwc.getSr() != null && srwc.getCallback() != null) {
        // Download the snapshot to the destination
        snapshotTransfer.download(srwc.getSr());

        // Callback with snapshot ID
        String snapshotPoint = blockManager.executeCallback(srwc.getCallback(), srwc.getSr());
      } else {
        LOG.warn("Failed to download base " + snap.getSnapshotId() + " for restoring " + snapshotId);
        throw new EucalyptusCloudException("Failed to download base " + snap.getSnapshotId() + " for restoring " + snapshotId);
      }
    } else {
      // doomed!
    }
  }

  private void downloadAndRestoreDelta(SnapshotInfo snap, SnapshotInfo prevSnap) throws Exception {
    String bucket = null;
    String key = null;

    if (StringUtils.isBlank(snap.getSnapshotLocation())) {
      throw new EucalyptusCloudException(
          "Snapshot location (bucket, key) for " + snap.getSnapshotId() + " is unknown. Cannot download snapshot from objectstorage.");
    }
    String[] names = SnapshotInfo.getSnapshotBucketKeyNames(snap.getSnapshotLocation());
    bucket = names[0];
    key = names[1];
    if (StringUtils.isBlank(bucket) || StringUtils.isBlank(key)) {
      throw new EucalyptusCloudException(
          "Failed to parse bucket and key information for downloading " + snap.getSnapshotId() + ". Cannot download snapshot from objectstorage.");
    }
    SnapshotTransfer snapshotTransfer = new S3SnapshotTransfer(snap.getSnapshotId(), bucket, key);
    Path diffPath = Files.createTempFile(snap.getSnapshotId() + "_" + prevSnap.getSnapshotId() + "_", ".diff");

    // Download snapshot delta
    StorageResource sr = new FileResource(snap.getSnapshotId(), diffPath.toString());
    snapshotTransfer.download(sr);

    // Apply the snapshot delta
    blockManager.restoreSnapshotDelta(snap.getSnapshotId(), prevSnap.getSnapshotId(), snapshotId, sr);
  }

}
