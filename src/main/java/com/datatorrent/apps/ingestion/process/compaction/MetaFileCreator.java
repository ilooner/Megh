/*
 *  Copyright (c) 2015 DataTorrent, Inc.
 *  All Rights Reserved.
 */
package com.datatorrent.apps.ingestion.process.compaction;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.datatorrent.api.BaseOperator;
import com.datatorrent.api.DefaultInputPort;
import com.datatorrent.api.DefaultOutputPort;
import com.datatorrent.apps.ingestion.process.compaction.PartitionMetaDataEmitter.FileInfoBlockMetadata;
import com.datatorrent.apps.ingestion.process.compaction.PartitionMetaDataEmitter.FilePartitionInfo;
import com.datatorrent.apps.ingestion.process.compaction.PartitionMetaDataEmitter.PatitionMetaData;

/**
 * Operator for synchronizing metaFile entries in .CompactionMeta file.
 * Entry in the metaFile is added only after all partitions for a particular file are written to outputFS  
 */
public class MetaFileCreator extends BaseOperator
{
  /**
   * Lookup from relative path of the file to {@link FileCompletionStatus}
   */
  private Map<String, FileCompletionStatus> fileCompletionStatusMap = new HashMap<String, FileCompletionStatus>();

  /**
   * MetaData for new partition is available on this input port.
   */
  public transient DefaultInputPort<FilePartitionInfo> filePartitionInfoPort = new DefaultInputPort<FilePartitionInfo>() {
    @Override
    public void process(FilePartitionInfo filePartitionInfo)
    {
      LOG.debug("Received File partition info{}", filePartitionInfo.ingestionFileMetaData.getRelativePath());
      processFilePartitionInfo(filePartitionInfo);
    }
  };

  /**
   * Adds an entry into the map holding file completion status
   * @param filePartitionInfo
   */
  private void processFilePartitionInfo(FilePartitionInfo filePartitionInfo)
  {
    if(filePartitionInfo.ingestionFileMetaData.isDirectory()){
      IndexEntry indexEntry = new IndexEntry(filePartitionInfo);
      indexEntryOuputPort.emit(indexEntry.toString());
    }
    else if(! fileCompletionStatusMap.containsKey(filePartitionInfo.ingestionFileMetaData.getRelativePath())){
      fileCompletionStatusMap.put(filePartitionInfo.ingestionFileMetaData.getRelativePath(), new FileCompletionStatus(filePartitionInfo));
    }
  }

  /**
   * Trigger indicating a partition completing its write to outputFS
   */
  public transient DefaultInputPort<PatitionMetaData> partitionCompleteTrigger = new DefaultInputPort<PatitionMetaData>() {
    @Override
    public void process(PatitionMetaData partitionMetaData)
    {
      LOG.debug("Received completion trigger for partition {}", partitionMetaData.getPartitionID());
      processCompletedPartition(partitionMetaData);
    }
  };

  /**
   * Updates partition completion in the {@link FileCompletionStatus}
   * Emit index entry if all partitions of the file are available.
   * @param partitionMetaData
   */
  private void processCompletedPartition(PatitionMetaData partitionMetaData)
  {
    for(FileInfoBlockMetadata fileBlock : partitionMetaData.blockMetaDataList){
      FileCompletionStatus status = fileCompletionStatusMap.get(fileBlock.getSourceRelativePath()) ;
      status.markPartitionAsComplete(partitionMetaData.getPartitionID());
      if(status.isFileComplete()){
        IndexEntry indexEntry = new IndexEntry(status.filePartitionInfo);
        indexEntryOuputPort.emit(indexEntry.toString());
        //Remove key from completion status map
        fileCompletionStatusMap.remove(fileBlock.getSourceRelativePath());
      }
    }
  }
  
  private static final Logger LOG = LoggerFactory.getLogger(MetaFileCreator.class);
  
  /**
   * Output port for emiting MetaFile entry.
   * One tuple will be emited per input file.
   */
  public final transient DefaultOutputPort<String> indexEntryOuputPort = new DefaultOutputPort<String>();
  
  /**
   * Information about which partitions of the file are completed
   */
  private static class FileCompletionStatus
  {
    /**
     * File metadata along with start, end partition:offset
     */
    FilePartitionInfo filePartitionInfo;
    
    /**
     * BitSet holds one bit for each partition. bit0 for start partition. ... bitk for end partition.
     * Bit is set when partition is complete.
     */
    BitSet completedPartitions;
    
    /**
     * Number of partitions for this file.
     * Assuming there wont be more than MAX_INT partitions for a particular file.
     */
    int noOfPartitions;

    public FileCompletionStatus()
    {
      super();
    }
    
    public FileCompletionStatus(FilePartitionInfo filePartitionInfo)
    {
      this.filePartitionInfo = filePartitionInfo;
      noOfPartitions = (int) (filePartitionInfo.endPartitionId - filePartitionInfo.startPartitionId + 1);
      completedPartitions = new BitSet(noOfPartitions);
    }
    
    /**
     * Sets a bit for a particular partition indicating it is complete. 
     * @param partitionId
     */
    void markPartitionAsComplete(long partitionId){
      int bitIndex = (int) (partitionId - filePartitionInfo.startPartitionId);
      completedPartitions.set(bitIndex);
      LOG.debug("setting bit for file {} : partition{}", filePartitionInfo.ingestionFileMetaData.getRelativePath(), partitionId);
    }
    
    /**
     * File is completed when all the partitions spanned by the file are complete
     * @return
     */
    boolean isFileComplete(){
      LOG.debug("completed partitions for file {} : {}", filePartitionInfo.ingestionFileMetaData.getRelativePath(), completedPartitions.cardinality());
      return (completedPartitions.cardinality() == noOfPartitions);
    }

  }
  
  /**
   * Entry in the .ComapctionMeta File. 
   */
  public static class IndexEntry{
    /**
     * File metadata along with start, end partition:offset
     */
    FilePartitionInfo filePartitionInfo;
    
    public IndexEntry()
    {
    }
    
    public IndexEntry(FilePartitionInfo filePartitionInfo)
    {
      this.filePartitionInfo = filePartitionInfo;
    }
    
    /**
     * Output of this method will be written to .index file
     */
    @Override
    public String toString()
    {
      StringBuffer sb = new StringBuffer();
      if(filePartitionInfo.ingestionFileMetaData.isDirectory()){
        sb.append("d  ");
        sb.append(String.format("%16s","-"));
        sb.append(String.format("%16s","-"));
        sb.append(String.format("%16s","-"));
        sb.append(String.format("%16s ","-"));
      }
      else{
        sb.append("-  ");
        sb.append(String.format("%16d",filePartitionInfo.startPartitionId));
        sb.append(String.format("%16d",filePartitionInfo.startOffset));
        sb.append(String.format("%16d",filePartitionInfo.endPartitionId));
        sb.append(String.format("%16d ",filePartitionInfo.endOffset));
      }
      sb.append(filePartitionInfo.ingestionFileMetaData.getRelativePath());
      sb.append('\n');
      return sb.toString();
    }
  }
}
