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
package org.apache.pig.test;


import java.io.*;
import java.util.Iterator;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.apache.pig.EvalFunc;
import org.apache.pig.ExecType;
import org.apache.pig.FuncSpec;
import org.apache.pig.PigServer;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.data.BagFactory;
import org.apache.pig.data.DataBag;
import org.apache.pig.data.Tuple;
import org.apache.pig.test.utils.TestHelper;
import org.junit.After;
import org.junit.Before;

public class TestSkewedJoin extends TestCase{
    private static final String INPUT_FILE1 = "SkewedJoinInput1.txt";
    private static final String INPUT_FILE2 = "SkewedJoinInput2.txt";
    private static final String INPUT_FILE3 = "SkewedJoinInput3.txt";
    
    private PigServer pigServer;
    private MiniCluster cluster = MiniCluster.buildCluster();
    
    public TestSkewedJoin() throws ExecException, IOException{
        pigServer = new PigServer(ExecType.MAPREDUCE, cluster.getProperties());
        // pigServer = new PigServer(ExecType.LOCAL);
        pigServer.getPigContext().getProperties().setProperty("pig.skewedjoin.reduce.maxtuple", "5");     
        pigServer.getPigContext().getProperties().setProperty("pig.skewedjoin.reduce.memusage", "0.1");
    }
    
    @Before
    public void setUp() throws Exception {
        createFiles();
    }

    private void createFiles() throws IOException {
    	PrintWriter w = new PrintWriter(new FileWriter(INPUT_FILE1));
    	    	
    	int k = 0;
    	for(int j=0; j<12; j++) {   	           	        
   	        w.println("100\tapple1\taaa" + k);
    	    k++;
    	    w.println("200\torange1\tbbb" + k);
    	    k++;
    	    w.println("300\tstrawberry\tccc" + k);
    	    k++;    	        	    
    	}
    	
    	w.close();

    	PrintWriter w2 = new PrintWriter(new FileWriter(INPUT_FILE2));
    	w2.println("100\tapple1");
    	w2.println("100\tapple2");
    	w2.println("100\tapple2");
    	w2.println("200\torange1");
    	w2.println("200\torange2");
    	w2.println("300\tstrawberry");    	
    	w2.println("400\tpear");

    	w2.close();
    	
    	PrintWriter w3 = new PrintWriter(new FileWriter(INPUT_FILE3));
    	w3.println("100\tapple1");
    	w3.println("100\tapple2");
    	w3.println("200\torange1");
    	w3.println("200\torange2");
    	w3.println("300\tstrawberry");
    	w3.println("300\tstrawberry2");
    	w3.println("400\tpear");

    	w3.close();
    	
    	Util.copyFromLocalToCluster(cluster, INPUT_FILE1, INPUT_FILE1);
    	Util.copyFromLocalToCluster(cluster, INPUT_FILE2, INPUT_FILE2);
    	Util.copyFromLocalToCluster(cluster, INPUT_FILE3, INPUT_FILE3);
    }
    
    @After
    public void tearDown() throws Exception {
    	new File(INPUT_FILE1).delete();
    	new File(INPUT_FILE2).delete();
    	new File(INPUT_FILE3).delete();
    	
        Util.deleteFile(cluster, INPUT_FILE1);
        Util.deleteFile(cluster, INPUT_FILE2);
        Util.deleteFile(cluster, INPUT_FILE3);
    }
    
    
    public void testSkewedJoinWithGroup() throws IOException{
        pigServer.registerQuery("A = LOAD '" + INPUT_FILE1 + "' as (id, name, n);");
        pigServer.registerQuery("B = LOAD '" + INPUT_FILE2 + "' as (id, name);");
        pigServer.registerQuery("C = GROUP A by id;");
        pigServer.registerQuery("D = GROUP B by id;");
        
        DataBag dbfrj = BagFactory.getInstance().newDefaultBag(), dbshj = BagFactory.getInstance().newDefaultBag();
        {
            pigServer.registerQuery("E = join C by group, D by group using \"skewed\" parallel 5;");
            Iterator<Tuple> iter = pigServer.openIterator("E");
            
            while(iter.hasNext()) {
                dbfrj.add(iter.next());
            }
        }
        {
            pigServer.registerQuery("E = join C by group, D by group;");
            Iterator<Tuple> iter = pigServer.openIterator("E");
            
            while(iter.hasNext()) {
                dbshj.add(iter.next());
            }
        }
        Assert.assertTrue(dbfrj.size()>0 && dbshj.size()>0);
        Assert.assertEquals(true, TestHelper.compareBags(dbfrj, dbshj));
    }      
    
    public void testSkewedJoinReducers() throws IOException{
        pigServer.registerQuery("A = LOAD '" + INPUT_FILE1 + "' as (id, name, n);");
        pigServer.registerQuery("B = LOAD '" + INPUT_FILE2 + "' as (id, name);");
        try {
            DataBag dbfrj = BagFactory.getInstance().newDefaultBag();
            {
                pigServer.registerQuery("C = join A by id, B by id using \"skewed\" parallel 1;");
                Iterator<Tuple> iter = pigServer.openIterator("C");
                
                while(iter.hasNext()) {
                    dbfrj.add(iter.next());
                }
            }
        }catch(Exception e) {
        	return;
        }
        
        fail("Should throw exception, not enough reducers");
    }
    
    public void testSkewedJoin3Way() throws IOException{
        pigServer.registerQuery("A = LOAD '" + INPUT_FILE1 + "' as (id, name, n);");
        pigServer.registerQuery("B = LOAD '" + INPUT_FILE2 + "' as (id, name);");
        pigServer.registerQuery("C = LOAD '" + INPUT_FILE3 + "' as (id, name);");
        try {
            DataBag dbfrj = BagFactory.getInstance().newDefaultBag();
            {
                pigServer.registerQuery("D = join A by id, B by id, C by id using \"skewed\" parallel 5;");
                Iterator<Tuple> iter = pigServer.openIterator("D");
                
                while(iter.hasNext()) {
                    dbfrj.add(iter.next());
                }
            }
        }catch(Exception e) {
        	return;
        }
        
        fail("Should throw exception, do not support 3 way join");
    }       
}