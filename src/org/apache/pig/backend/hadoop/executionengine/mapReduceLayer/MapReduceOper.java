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
package org.apache.pig.backend.hadoop.executionengine.mapReduceLayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.plans.MROpPlanVisitor;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POUnion;
import org.apache.pig.impl.plan.Operator;
import org.apache.pig.impl.plan.PlanException;
import org.apache.pig.impl.plan.VisitorException;

/**
 * An operator model for a Map Reduce job. 
 * Acts as a host to the plans that will
 * execute in map, reduce and optionally combine
 * phases. These will be embedded in the MROperPlan
 * in order to capture the dependecies amongst jobs.
 */
public class MapReduceOper extends Operator<MROpPlanVisitor> {
    private static final long serialVersionUID = 1L;

    //The physical plan that should be executed
    //in the map phase
    public PhysicalPlan mapPlan;
    
    //The physical plan that should be executed
    //in the reduce phase
    public PhysicalPlan reducePlan;
    
    //The physical plan that should be executed
    //in the combine phase if one exists. Will be used
    //by the optimizer.
    public PhysicalPlan combinePlan;
    
    // key for the map plan
    // this is needed when the key is null to create
    // an appropriate NullableXXXWritable object
    public byte mapKeyType;
    
    //Indicates that the map plan creation
    //is complete
    boolean mapDone = false;
    
    //Indicates that the reduce plan creation
    //is complete
    boolean reduceDone = false;
    
    // Indicates that there is an operator which uses endOfAllInput flag in the 
    // map plan
    boolean endOfAllInputInMap = false;
    
    // Indicates that there is an operator which uses endOfAllInput flag in the 
    // reduce plan
    boolean endOfAllInputInReduce = false;;
    
    //Indicates if this job is an order by job
    boolean globalSort = false;

    // Indicates if this is a limit after a sort
    boolean limitAfterSort = false;
    
    // Indicate if the entire purpose for this map reduce job is doing limit, does not change
    // anything else. This is to help POPackageAnnotator to find the right POPackage to annotate
    boolean limitOnly = false;

    // If true, putting an identity combine in this
    // mapreduce job will speed things up.
    boolean needsDistinctCombiner = false;
    
    // If true, we will use secondary key in the map-reduce job
    boolean useSecondaryKey = false;
    
    //The quantiles file name if globalSort is true
    String quantFile;
    
    //The sort order of the columns;
    //asc is true and desc is false
    boolean[] sortOrder;
    
    // Sort order for secondary keys;
    boolean[] secondarySortOrder;

    public List<String> UDFs;
    
    transient NodeIdGenerator nig;

    private String scope;
    
    
    //Fragment Replicate Join State
    boolean frjoin = false;
    FileSpec[] replFiles = null;
    int fragment = -1;
    
    int requestedParallelism = -1;
    
    // Last POLimit value in this map reduce operator, needed by LimitAdjuster
    // to add additional map reduce operator with 1 reducer after this
    long limit = -1;

    // Indicates that this MROper is a splitter MROper. 
    // That is, this MROper ends due to a POSPlit operator.
    private boolean splitter = false;

	// Set to true if it is skewed join
	private boolean skewedJoin = false;

    // Name of the partition file generated by sampling process,
    // Used by Skewed Join
	private String skewedJoinPartitionFile;

    public MapReduceOper(OperatorKey k) {
        super(k);
        mapPlan = new PhysicalPlan();
        combinePlan = new PhysicalPlan();
        reducePlan = new PhysicalPlan();
        UDFs = new ArrayList<String>();
        nig = NodeIdGenerator.getGenerator();
        scope = k.getScope();
    }

    /*@Override
    public String name() {
        return "MapReduce - " + mKey.toString();
    }*/
    
    private String shiftStringByTabs(String DFStr, String tab) {
        StringBuilder sb = new StringBuilder();
        String[] spl = DFStr.split("\n");
        for (int i = 0; i < spl.length; i++) {
            sb.append(tab);
            sb.append(spl[i]);
            sb.append("\n");
        }
        sb.delete(sb.length() - "\n".length(), sb.length());
        return sb.toString();
    }
    
    /**
     * Uses the string representation of the 
     * component plans to identify itself.
     */
    @Override
    public String name() {
        String udfStr = getUDFsAsStr();
        
        StringBuilder sb = new StringBuilder("MapReduce" + "(" + requestedParallelism + 
                (udfStr.equals("")? "" : ",") + udfStr + ")" + " - " + mKey.toString()
                + ":\n");
        int index = sb.length();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if(!mapPlan.isEmpty()){
            mapPlan.explain(baos);
            String mp = new String(baos.toByteArray());
            sb.append(shiftStringByTabs(mp, "|   "));
        }
        else
            sb.append("Map Plan Empty");
        if (!reducePlan.isEmpty()){
            baos.reset();
            reducePlan.explain(baos);
            String rp = new String(baos.toByteArray());
            sb.insert(index, shiftStringByTabs(rp, "|   ") + "\n");
        }
        else
            sb.insert(index, "Reduce Plan Empty" + "\n");
        return sb.toString();
    }

    private String getUDFsAsStr() {
        StringBuilder sb = new StringBuilder();
        if(UDFs!=null && UDFs.size()>0){
            for (String str : UDFs) {
                sb.append(str.substring(str.lastIndexOf('.')+1));
                sb.append(',');
            }
            sb.deleteCharAt(sb.length()-1);
        }
        return sb.toString();
    }

    @Override
    public boolean supportsMultipleInputs() {
        return true;
    }

    @Override
    public boolean supportsMultipleOutputs() {
        return true;
    }

    @Override
    public void visit(MROpPlanVisitor v) throws VisitorException {
        v.visitMROp(this);
    }
    
    public boolean isMapDone() {
        return mapDone;
    }
    
    public void setMapDone(boolean mapDone){
        this.mapDone = mapDone;
    }
    
    public void setMapDoneSingle(boolean mapDone) throws PlanException{
        this.mapDone = mapDone;
        if (mapDone && mapPlan.getLeaves().size()>1) {
            mapPlan.addAsLeaf(getUnion());
        }
    }
    
    public void setMapDoneMultiple(boolean mapDone) throws PlanException{
        this.mapDone = mapDone;
        if (mapDone && mapPlan.getLeaves().size()>0) {
            mapPlan.addAsLeaf(getUnion());
        }
    }
    
    private POUnion getUnion(){
        return new POUnion(new OperatorKey(scope,nig.getNextNodeId(scope)));
    }
    
    public boolean isReduceDone() {
        return reduceDone;
    }

    public void setReduceDone(boolean reduceDone){
        this.reduceDone = reduceDone;
    }
    
    public boolean isGlobalSort() {
        return globalSort;
    }
    
    public boolean isSkewedJoin() {
    	return (skewedJoinPartitionFile != null);
    }
    
    public void setSkewedJoinPartitionFile(String file) {    	
    	skewedJoinPartitionFile = file;
    }
    
    public String getSkewedJoinPartitionFile() {
    	return skewedJoinPartitionFile;
    }

	public void setSkewedJoin(boolean skJoin) {
		this.skewedJoin = skJoin;
	}

	public boolean getSkewedJoin() {
		return skewedJoin;
	}

    public void setGlobalSort(boolean globalSort) {
        this.globalSort = globalSort;
    }

    public boolean isLimitAfterSort() {
        return limitAfterSort;
    }

    public void setLimitAfterSort(boolean las) {
        limitAfterSort = las;
    }
    
    public boolean isLimitOnly() {
        return limitOnly;
    }
    
    public void setLimitOnly(boolean limitOnly) {
        this.limitOnly = limitOnly;
    }

    public boolean needsDistinctCombiner() { 
        return needsDistinctCombiner;
    }

    public void setNeedsDistinctCombiner(boolean nic) {
        needsDistinctCombiner = nic;
    }

    public String getQuantFile() {
        return quantFile;
    }

    public void setQuantFile(String quantFile) {
        this.quantFile = quantFile;
    }

    public void setSortOrder(boolean[] sortOrder) {
        if(null == sortOrder) return;
        this.sortOrder = new boolean[sortOrder.length];
        for(int i = 0; i < sortOrder.length; ++i) {
            this.sortOrder[i] = sortOrder[i];
        }
    }
    
    public void setSecondarySortOrder(boolean[] secondarySortOrder) {
        if(null == secondarySortOrder) return;
        this.secondarySortOrder = new boolean[secondarySortOrder.length];
        for(int i = 0; i < secondarySortOrder.length; ++i) {
            this.secondarySortOrder[i] = secondarySortOrder[i];
        }
    }
             
    public boolean[] getSortOrder() {
        return sortOrder;
    }

    public boolean[] getSecondarySortOrder() {
        return secondarySortOrder;
    }

    /**
     * @return whether end of all input is set in the map plan
     */
    public boolean isEndOfAllInputSetInMap() {
        return endOfAllInputInMap;
    }

    /**
     * @param endOfAllInputInMap the streamInMap to set
     */
    public void setEndOfAllInputInMap(boolean endOfAllInputInMap) {
        this.endOfAllInputInMap = endOfAllInputInMap;
    }

    /**
     * @return whether end of all input is set in the reduce plan
     */
    public boolean isEndOfAllInputSetInReduce() {
        return endOfAllInputInReduce;
    }

    /**
     * @param endOfAllInputInReduce the streamInReduce to set
     */
    public void setEndOfAllInputInReduce(boolean endOfAllInputInReduce) {
        this.endOfAllInputInReduce = endOfAllInputInReduce;
    }    
    public int getFragment() {
        return fragment;
    }

    public void setFragment(int fragment) {
        this.fragment = fragment;
    }

    public boolean isFrjoin() {
        return frjoin;
    }

    public void setFrjoin(boolean frjoin) {
        this.frjoin = frjoin;
    }

    public FileSpec[] getReplFiles() {
        return replFiles;
    }

    public void setReplFiles(FileSpec[] replFiles) {
        this.replFiles = replFiles;
    }

    public int getRequestedParallelism() {
        return requestedParallelism;
    }

    public void setSplitter(boolean spl) {
        splitter = spl;
    }

    public boolean isSplitter() {
        return splitter;
    }
    
    public boolean getUseSecondaryKey() {
        return useSecondaryKey;
    }
    
    public void setUseSecondaryKey(boolean useSecondaryKey) {
        this.useSecondaryKey = useSecondaryKey;
    }
}
