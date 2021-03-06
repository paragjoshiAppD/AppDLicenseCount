/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.appdynamics.licensecount.data;

import org.appdynamics.licensecount.actions.*;
import org.appdynamics.appdrestapi.RESTAccess;
import org.appdynamics.appdrestapi.data.*;
import org.appdynamics.appdrestapi.util.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import org.appdynamics.appdrestapi.resources.s;

import java.util.logging.Logger;
import java.util.logging.Level;

/**
 *
 * @author soloink
 * 
 * When using a range the tier is going to calculate the range values,
 * that means that every time we calculate the values, the tierLicenseCount
 * has to be reinitialized. 
 * 
 * 
 */
public class TierLicenseCount extends LicenseCount{
    private static Logger logger=Logger.getLogger(TierLicenseCount.class.getName());
    private String name;
    private String tierAgentType;
    private int tierId;
    private int nodeCount=0;

    private ArrayList<NodeLicenseCount> nodeLicenseCount=new ArrayList<NodeLicenseCount>();
    private ArrayList<TierLicenseRange> tierLicenseRange=new ArrayList<TierLicenseRange>();
    private ArrayList<TierHourLicenseRange> tierHourLicenseRange=new ArrayList<TierHourLicenseRange>();
    private TierLicenseRange totalRangeValue;
    
    public TierLicenseCount(){super();}
    
    public TierLicenseCount(String name){super();this.name=name;}
    
    public TierLicenseCount(String name, String tierType, int id){
        super();
        this.name=name;
        this.tierAgentType=tierType;
        this.tierId=id;
    }
    
    public void addNode(Node node){
        nodeCount++;
        nodeLicenseCount.add(new NodeLicenseCount(node));
    }
    
    // We return the node so that we can work with it.
    public NodeLicenseCount addNodeRange(Node node){
        
        NodeLicenseCount nodeL=null;
        try{
            nodeL=new NodeLicenseCount(node);
            nodeCount++;
            nodeLicenseCount.add(nodeL);
        }catch(Exception e){
            StringBuilder bud =new StringBuilder();
            bud.append("Exception occurred while attempting to get node information for ").append(node.toString()).append("\n\t\tNode will not be counted.");
            logger.log(Level.SEVERE, bud.toString());
        }    
        return nodeL;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public ArrayList<NodeLicenseCount> getNodeLicenseCount() {
        return nodeLicenseCount;
    }

    public void setNodeLicenseCount(ArrayList<NodeLicenseCount> nodeLicenseCount) {
        this.nodeLicenseCount = nodeLicenseCount;
    }

    public String getTierAgentType() {
        return tierAgentType;
    }

    public void setTierAgentType(String tierAgentType) {
        this.tierAgentType = tierAgentType;
    }

    public int getTierId() {
        return tierId;
    }

    public void setTierId(int tierId) {
        this.tierId = tierId;
    }
    
    
    
    public void updateLicenseWeight(double weight, Node node){
        for(NodeLicenseCount nodeLic: getNodeLicenseCount()){
            if(nodeLic.getNode().getId() == node.getId()) nodeLic.setLicWeight(weight);
        }
    }
    
    public void populateNodeLicenseRange(TimeRange totalTimeRange, ArrayList<TimeRange> timeRanges, 
            RESTAccess access, String applicationName){
        
        if(s.debugLevel >= 2) 
            logger.log(Level.INFO,new StringBuilder().append("Populating tier ").append(name).append(" license count for application ").append(applicationName).toString());
        
        totalRangeValue=new TierLicenseRange("Tier Total Count");
        totalRangeValue.setStart(totalTimeRange.getStart());totalRangeValue.setEnd(totalTimeRange.getEnd());
        
        /*
         * This is going to get the nodes to count all of the licenses.
         * 
         * This is a good point to thread out
         */
        ThreadExecutor execNodes = new ThreadExecutor(8);
        
        for(NodeLicenseCount nodeL:nodeLicenseCount){
            //public NodeExecutor(NodeLicenseCount nodeLic,RESTAccess access, 
            //String appName, TimeRange totalTimeRange, 
            //ArrayList<TimeRange> timeRanges, String tierAgentType)
            NodeExecutor nodeExec = new NodeExecutor(nodeL, access, applicationName, totalTimeRange, timeRanges, tierAgentType);
            execNodes.getExecutor().execute(nodeExec);
            //nodeL.populateNodeLicenseRange(totalTimeRange, timeRanges, access, applicationName, tierAgentType);
        }
        execNodes.getExecutor().shutdown();
        execNodes.shutdown();
        
        MetricDatas tierAppAgents=access.getRESTMetricQuery(0, applicationName, name, totalTimeRange.getStart(), totalTimeRange.getEnd());
        MetricDatas tierMachineAgents=access.getRESTMetricQuery(1, applicationName, name, totalTimeRange.getStart(), totalTimeRange.getEnd());
        
        //This call doesn't do anything --delete
        getMetricValues(tierAppAgents).getMetricValue();
        
        // This is serial
        ArrayList<TimeRange> hourlyTimeRanges=TimeRangeHelper.getHourlyTimeRanges(totalTimeRange.getStart(), totalTimeRange.getEnd());
        for(TimeRange hourRange: hourlyTimeRanges){
            TierHourLicenseRange tr=new TierHourLicenseRange(hourRange);
            tr.createName();
            
            for(MetricValue mv:getMetricValues(tierAppAgents).getMetricValue()){
                if(tr.withIn(mv.getStartTimeInMillis())) tr.getAppMetricValues().getMetricValue().add(mv);
            }
            
            for(MetricValue mv:getMetricValues(tierMachineAgents).getMetricValue()){
                if(tr.withIn(mv.getStartTimeInMillis())) tr.getMachineMetricValues().getMetricValue().add(mv);
            }
            tr.countAgents();
            tierHourLicenseRange.add(tr);
        }
        
        
    }
    
    public String getNodeKey(TierLicenseRange tRange, String machineName){
        StringBuilder bud = new StringBuilder();
        bud.append(tRange.getStart()).append("-").append(tRange.getEnd()).append("-").append(machineName);        
        return bud.toString();
    }
    
    //0:Java, 1:IIS, 2:PHP, 3:NodeJS, 4 Machine Agent
    public void countNodeLicenses(ArrayList<TimeRange> timeRanges,HashMap<String,ArrayList<Node>> dotNetMap, ArrayList<String> dotNetKeys){
        
   
        logger.log(Level.INFO,new StringBuilder().append("Starting tier level license count for tier ").append(name).toString());
        
        for(NodeLicenseCount nodeL:nodeLicenseCount){
            nodeL.countNodeLicenseRange(s.percentageThreshold);  
        } 
        
        for(int i=0; i < timeRanges.size(); i++){
            TierLicenseRange tRange = new TierLicenseRange();
            tRange.setStart(timeRanges.get(i).getStart());
            tRange.setEnd(timeRanges.get(i).getEnd());
            tRange.setName(tRange.createName());
            HashMap<String,ArrayList<Node>> dotNetMapTemp=new HashMap<String,ArrayList<Node>>(dotNetMap);
            ArrayList<String> found=new ArrayList<String>();
            for(NodeLicenseCount node:nodeLicenseCount){
                if(node.getRangeValues().size() > i && node.getRangeValues().get(i).isCountAsLicense()){
//                    if(s.debugLevel >= 2) 
//            logger.log(Level.INFO,new StringBuilder().append("\t\tCounting node type ").append(node.getType()).toString());
                    switch(node.getType()){
                        case 1:
                            //We don't do anything for now, this is will be added up later.
                            //logger.log(Level.INFO,"Adding DotNet " + node.getLicWeight());
                            
                            StringBuilder bud = new StringBuilder();
                            bud.append("\n\tAdding .Net node ").append(node.getNode().getName()).append("\n\tiisCount orig value ").append(tRange.iisCount);
                            if(dotNetMapTemp.containsKey(node.getMachineName()) && !found.contains(node.getMachineName())
                                    && ! dotNetKeys.contains(getNodeKey(tRange,node.getMachineName()))){
                                tRange.iisCount++;
                                tRange.totalCount++;
                                found.add(node.getMachineName());//dotNetMapTemp.remove(node.getMachineName());
                                dotNetKeys.add(getNodeKey(tRange,node.getMachineName()));
                                bud.append("\n----------------------------------------\n start:").append(tRange.getStart()).append(" -- ").append(tRange.getEnd());
                                bud.append(" :: ").append(node.getNode().getTierName());
                            }
                            bud.append(" and iisInternalCount orig value ").append(tRange.iisInternalCount);
                            tRange.iisInternalCount++;
                            bud.append("\n\tiisCount new value ").append(tRange.iisCount).append(" and iisInternalCount new value ").append(tRange.iisInternalCount);
                            //logger.log(Level.INFO,bud.toString());
                            //tRange.iisCount+=node.getLicWeight();
                            //tRange.totalCount+=node.getLicWeight();
                            break;
                        case 2:
                            //We don't do anything for now, this will be added up later
                            //logger.log(Level.INFO,"Adding PHP " + node.getLicWeight());
                            tRange.phpCount++;//=node.getLicWeight();
                            tRange.totalCount++;//=node.getLicWeight();
                            break;

                        case 3:
                            tRange.nodeJSCount++;
                            tRange.totalCount++;
                            break;
                        case 4:
                            tRange.machineCount++;
                            tRange.totalCount++;
                            break;
                        default:
                            tRange.javaCount++;
                            tRange.totalCount++;
                            break;
                    }
                    
                }
            }
            tierLicenseRange.add(tRange);
        }
        
        for(TierLicenseRange tRange: tierLicenseRange){
            totalRangeValue.iisCount+=tRange.iisCount;
            totalRangeValue.javaCount+=tRange.javaCount;
            totalRangeValue.phpCount+=tRange.phpCount;
            totalRangeValue.nodeJSCount+=tRange.nodeJSCount;
            totalRangeValue.machineCount+=tRange.machineCount;
            totalRangeValue.totalCount+=tRange.totalCount;
            totalRangeValue.iisInternalCount+=tRange.iisInternalCount;
        }
    }
    
    
    public ArrayList<TierLicenseRange> getTierLicenseRange() {
        return tierLicenseRange;
    }

    public void setTierLicenseCount(ArrayList<TierLicenseRange> tierLicenseRange) {
        this.tierLicenseRange = tierLicenseRange;
    }

    public TierLicenseRange getTotalRangeValue() {
        return totalRangeValue;
    }

    public void setTotalRangeValue(TierLicenseRange totalRangeValue) {
        this.totalRangeValue = totalRangeValue;
    }

    public ArrayList<TierHourLicenseRange> getTierHourLicenseRange() {
        return tierHourLicenseRange;
    }

    public void setTierHourLicenseRange(ArrayList<TierHourLicenseRange> tierHourLicenseRange) {
        this.tierHourLicenseRange = tierHourLicenseRange;
    }
    

    
    @Override
    public String toString(){
        StringBuilder bud=new StringBuilder();
        bud.append("Tier Name: ").append(name).append("\n");
        bud.append("Tier Node Count: ").append(nodeCount).append("\n");
        //bud.append(totalRangeValue.toString());
        bud.append("---------------------------------------------------------------------\n");
        for(TierLicenseRange tRange: tierLicenseRange){
            bud.append(tRange.toString());
        }
        for(TierHourLicenseRange tRange: tierHourLicenseRange){
            bud.append(tRange.toString());
        }
        return bud.toString();
    }
    
    
    
}
