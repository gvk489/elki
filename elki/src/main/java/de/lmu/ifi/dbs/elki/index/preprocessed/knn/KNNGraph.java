package de.lmu.ifi.dbs.elki.index.preprocessed.knn;
/*
 This file is part of ELKI:
 Environment for Developing KDD-Applications Supported by Index-Structures

 Copyright (C) 2016
 Ludwig-Maximilians-Universität München
 Lehr- und Forschungseinheit für Datenbanksysteme
 ELKI Development Team

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import javax.swing.plaf.synth.SynthSeparatorUI;

import de.lmu.ifi.dbs.elki.database.datastore.memory.MapStore;
import de.lmu.ifi.dbs.elki.database.ids.DBIDIter;
import de.lmu.ifi.dbs.elki.database.ids.DBIDMIter;
import de.lmu.ifi.dbs.elki.database.ids.DBIDRef;
import de.lmu.ifi.dbs.elki.database.ids.DBIDUtil;
import de.lmu.ifi.dbs.elki.database.ids.DBIDs;
import de.lmu.ifi.dbs.elki.database.ids.DoubleDBIDListIter;
import de.lmu.ifi.dbs.elki.database.ids.HashSetModifiableDBIDs;
import de.lmu.ifi.dbs.elki.database.ids.KNNHeap;
import de.lmu.ifi.dbs.elki.database.ids.KNNList;
import de.lmu.ifi.dbs.elki.database.query.DatabaseQuery;
import de.lmu.ifi.dbs.elki.database.query.distance.DistanceQuery;
import de.lmu.ifi.dbs.elki.database.query.knn.KNNQuery;
import de.lmu.ifi.dbs.elki.database.relation.Relation;
import de.lmu.ifi.dbs.elki.distance.distancefunction.DistanceFunction;
import de.lmu.ifi.dbs.elki.logging.Logging;
import de.lmu.ifi.dbs.elki.logging.progress.IndefiniteProgress;
import de.lmu.ifi.dbs.elki.logging.statistics.DoubleStatistic;
import de.lmu.ifi.dbs.elki.logging.statistics.LongStatistic;
import de.lmu.ifi.dbs.elki.logging.statistics.StringStatistic;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.OptionID;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameterization.Parameterization;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.DoubleParameter;
import de.lmu.ifi.dbs.elki.utilities.optionhandling.parameters.RandomParameter;
import de.lmu.ifi.dbs.elki.utilities.random.RandomFactory;

public class KNNGraph<O> extends AbstractMaterializeKNNPreprocessor<O> {
  /**
   * Logger
   */
  private static final Logging LOG = Logging.getLogger(KNNGraph.class);

  
  /**
   * Random generator
   */
  private final RandomFactory rnd;
  
  /**
   * total distance computations
   */
  private double counter_all=0.0;
  
  /**
   * new neighbors per iteration
   */
  private int t=0;
  
  /**
   * early termination parameter
   */
  private double delta = 0.001;
  
  /**
   * sample rate
   */
  private double rho = 1.0;
  
  /**
   * store for neighbors
   */
  private MapStore<KNNHeap> store = new MapStore<>();
  
  /**
   * store for new reverse neighbors
   */
  private MapStore<HashSetModifiableDBIDs> newReverseNeighbors = new MapStore<>();
  
  /**
   * store for new reverse neighbors
   */
  private MapStore<HashSetModifiableDBIDs> oldReverseNeighbors = new MapStore<>();
  
  /**
   * Constructor.
   *
   * @param relation Relation to index
   * @param distanceFunction distance function
   * @param k k
   * @param rnd Random generator
   */
  public KNNGraph(Relation<O> relation, DistanceFunction<? super O> distanceFunction, int k, RandomFactory rnd, double delta, double rho) {
    super(relation, distanceFunction, k);
    this.rnd = rnd;
    this.delta = delta;
    this.rho = rho;
  }
  
  @Override
  protected void preprocess() {
    final long starttime = System.currentTimeMillis();
    
    IndefiniteProgress progress = LOG.isVerbose() ? new IndefiniteProgress("KNNGraph iteration", LOG) : null;
    
    storage = new MapStore<KNNList>();
   
    MapStore<HashSetModifiableDBIDs> flag = new MapStore<HashSetModifiableDBIDs>();

    final int items = (int) Math.round(rho*k);
    
    //TODO passt so?
    KNNHeap dummyHeap = DBIDUtil.newHeap(k);
    //TODO c später löschen, aber wird im Source-Code auf- oder abgerundet bei rho?
    //S(K * S_), dann resize von S.. (S_ ist dabei rho)
    MapStore<HashSetModifiableDBIDs> sampleNewHash = new MapStore<HashSetModifiableDBIDs>();
    for(DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()) {
      final DBIDs sample = DBIDUtil.randomSample(relation.getDBIDs(), items, rnd);
      final DBIDs sample2 = DBIDUtil.randomSample(relation.getDBIDs(), items, rnd);
      sampleNewHash.put(iditer, DBIDUtil.newHashSet(sample));
      flag.put(iditer,DBIDUtil.newHashSet());
      newReverseNeighbors.put(iditer, DBIDUtil.newHashSet(sample2));
//      // initialize store
//      store.put(iditer, dummyHeap);
    }
    
    int size = relation.size();
    int counter = k * size;
    
    double rate = 0.0;

    while (counter >= delta*k*size && counter > 0){
      LOG.incrementProcessed(progress);
      counter = 0;
      
      //iterate through dataset
      for(DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()) {
        //determine new and old neighbors
        HashSetModifiableDBIDs newNeighbors = flag.get(iditer);
        HashSetModifiableDBIDs oldNeighbors = DBIDUtil.newHashSet();
        KNNHeap heap = store.get(iditer);
        //TODO hier statt null-Abfrage wegen Initialisierung oben size-Abfrage?? --> KOSTET MEHR
//        if (heap.size() > 0){
        if (heap != null){
          for (DoubleDBIDListIter heapiter = heap.unorderedIterator(); heapiter.valid(); heapiter.advance()){
            if (!newNeighbors.contains(heapiter)){
              oldNeighbors.add(heapiter);
            }
          }
        }
        
        
        
        //Sampling
        HashSetModifiableDBIDs sampleNew = sampleNewHash.get(iditer);
        
        HashSetModifiableDBIDs newRev = DBIDUtil.newHashSet();
        if (newReverseNeighbors.get(iditer) != null){
          newRev = newReverseNeighbors.get(iditer);
          newRev = DBIDUtil.newHashSet(DBIDUtil.randomSample(newRev, Math.min(items, newRev.size()), rnd));
        }

        HashSetModifiableDBIDs oldRev = DBIDUtil.newHashSet();
        if (oldReverseNeighbors.get(iditer) != null){
          oldRev = oldReverseNeighbors.get(iditer);
          oldRev = DBIDUtil.newHashSet(DBIDUtil.randomSample(oldRev, Math.min(items, oldRev.size()), rnd));
        }
        //TODO heap wegen Laufzeit wiederverwenden ??? --> dann concurrent?
        //3 loops
        //nn_new
          //nn_new
          //nn_old
        for(DBIDIter sniter = sampleNew.iter(); sniter.valid(); sniter.advance()) {
          for(DBIDIter niter2 = sampleNew.iter(); niter2.valid(); niter2.advance()){
            if (DBIDUtil.compare(sniter, niter2)<0){
              flag = addpair(flag, sniter, niter2);
              counter++;
            }
          }
          for(DBIDMIter niter2 = oldNeighbors.iter(); niter2.valid(); niter2.advance()) {       
            if (DBIDUtil.compare(sniter, niter2)!=0){
              flag = addpair(flag, sniter, niter2);
              counter++;
            }
          }
        }
        //rnn_new
          //rnn_new
          //rnn_old
        for(DBIDIter nriter = newRev.iter(); nriter.valid(); nriter.advance()) {
          for(DBIDIter niter2 = newRev.iter(); niter2.valid(); niter2.advance()){
            if (DBIDUtil.compare(nriter, niter2)<0){
              flag = addpair(flag, nriter, niter2);
              counter++;
            }
          }
          for(DBIDIter niter2 = oldRev.iter(); niter2.valid(); niter2.advance()) {       
            if (DBIDUtil.compare(nriter, niter2)!=0){
              flag = addpair(flag, nriter, niter2);
              counter++;
            }
          }
        }
        
        //nn_new
          //rnn_old
          //rnn_new
        for(DBIDIter sniter2 = sampleNew.iter(); sniter2.valid(); sniter2.advance()) {
          for(DBIDIter niter2 = oldRev.iter(); niter2.valid(); niter2.advance()){
            if (DBIDUtil.compare(sniter2, niter2)!=0){
              flag = addpair(flag, sniter2, niter2);
              counter++;
            }
          }
          for(DBIDIter niter2 = newRev.iter(); niter2.valid(); niter2.advance()) {       
            if (DBIDUtil.compare(sniter2, niter2)!=0){
              flag = addpair(flag, sniter2, niter2);
              counter++;
            }
          }
        }
        //nn_old
          //rnn_new
        for(DBIDIter niter = oldNeighbors.iter(); niter.valid(); niter.advance()) {
          for(DBIDIter niter2 = newRev.iter(); niter2.valid(); niter2.advance()){
            if (DBIDUtil.compare(niter, niter2)!=0){
              flag = addpair(flag, niter, niter2);
              counter++;
            }
          }
        }
      }
      counter_all+=counter;
      if (LOG.isStatistics()){
        LOG.statistics(new StringStatistic("Distance computations in this iteration", Integer.toString(counter)));
        LOG.statistics(new DoubleStatistic("Scan rate in this iteration", counter_all/(size*(size-1.0)/2.0)));
      }
      
      t=0;
      sampleNewHash = sampleNew(flag, items);      
      
      //calculate reverse neighbors separately for old and new
      newReverseNeighbors.clear();
      oldReverseNeighbors.clear();
      reverse(sampleNewHash);      
      
      rate = (double) t / (double) (k*size);
      if (LOG.isStatistics()){
        LOG.statistics(new DoubleStatistic("Update rate in this iteration", rate));
      }
      if (rate < delta) break;
    }
    //convert store to storage
    for(DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()) {
      KNNHeap heap = store.get(iditer);
      KNNList list = heap.toKNNList();
      storage.put(iditer, list);
    }
    LOG.setCompleted(progress);
    final long end = System.currentTimeMillis();
    if(LOG.isStatistics()) {
      LOG.statistics(new LongStatistic(this.getClass().getCanonicalName() + ".construction-time.ms", end - starttime));
    }
  }

  private MapStore<HashSetModifiableDBIDs> addpair(MapStore<HashSetModifiableDBIDs> newNeighborHash, DBIDIter niter, DBIDIter niter2) {
    HashSetModifiableDBIDs niternew = newNeighborHash.get(niter);
    HashSetModifiableDBIDs niternew2 = newNeighborHash.get(niter2);
    double distance = distanceQuery.distance(niter, niter2);
    int add = add (niter, niter2, distance);
    if (add > 0){
      niternew.add(niter2);
    }
    int add2 = add (niter2, niter, distance);
    if (add2 > 0){
      niternew2.add(niter);
    }
    newNeighborHash.put(niter2, niternew2);
    newNeighborHash.put(niter, niternew);
    return newNeighborHash;
  }
  /**
   * samples newNeighbors for every object
   * @param store - neighbors for every object
   * @param newNeighborHash - new neighbors for every object
   * @return sampled new Neighbors for every object
   */
  private MapStore<HashSetModifiableDBIDs> sampleNew(MapStore<HashSetModifiableDBIDs> newNeighborHash, int items) {
    MapStore<HashSetModifiableDBIDs> sampleNewNeighbors = new MapStore<>();
    for (DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()){
      KNNHeap realNeighbors = store.get(iditer);
      HashSetModifiableDBIDs newNeighbors = newNeighborHash.get(iditer);
      HashSetModifiableDBIDs realNewNeighbors = DBIDUtil.newHashSet();
      if (realNeighbors != null){
        for (DoubleDBIDListIter heapiter = realNeighbors.unorderedIterator(); heapiter.valid(); heapiter.advance()){
          if (newNeighbors.contains(heapiter)){
            realNewNeighbors.add(heapiter);
            t++;
          }
        }
      }
      HashSetModifiableDBIDs sampleNew = DBIDUtil.newHashSet(DBIDUtil.randomSample(realNewNeighbors, Math.min(items, realNewNeighbors.size()), rnd));
      sampleNewNeighbors.put(iditer, sampleNew);
      newNeighbors.removeDBIDs(sampleNew);
      newNeighborHash.put(iditer, newNeighbors);
    }
    return sampleNewNeighbors;
  }

  /**
   * calculates new and old neighbors for database
   * @param store - neighbors for every object
   * @param sampleNewHash - new neighbors for every object
   */
  private void reverse(MapStore<HashSetModifiableDBIDs> sampleNewHash) {
    for (DBIDIter iditer = relation.iterDBIDs(); iditer.valid(); iditer.advance()){
      KNNHeap heap = store.get(iditer);
      DBIDs newNeighbors = sampleNewHash.get(iditer);
      if (heap != null){
        for (DoubleDBIDListIter heapiter = heap.unorderedIterator(); heapiter.valid(); heapiter.advance()){
          if (newNeighbors.contains(heapiter)){
            HashSetModifiableDBIDs newReverse = newReverseNeighbors.get(heapiter);
            if (newReverse == null){
              newReverse = DBIDUtil.newHashSet();
            }
            newReverse.add(iditer);
            newReverseNeighbors.put(heapiter, newReverse);
          }
          else{
            HashSetModifiableDBIDs oldReverse = oldReverseNeighbors.get(heapiter);
            if (oldReverse== null){
              oldReverse = DBIDUtil.newHashSet();
            }
            oldReverse.add(iditer);
            oldReverseNeighbors.put(heapiter, oldReverse);
          }
        }
      }
    }
  }
  
  /**
   * 
   * add nniter to iditer-heap neighbors with distance
   *
   * @param iditer
   * @param nniter
   * @param distance
   * @return
   */
  private int add(DBIDRef iditer, DBIDRef nniter, double distance) {
    int ret = 0;
    KNNHeap neighbors = store.get(iditer);
    //see if actual object is already contained in hash
    boolean contained=false;
    if (neighbors != null){
      for (DoubleDBIDListIter heapiter = neighbors.unorderedIterator(); heapiter.valid(); heapiter.advance()){                 
        if (DBIDUtil.compare(heapiter, nniter)==0){
          contained=true;
        }
      }
    }
    else{
      neighbors = DBIDUtil.newHeap(k);
    }
    if (!contained){
      //calculate similarity of v and u2
      double newDistance = neighbors.insert(distance,nniter);
      if (distance <= newDistance){
        ret++;
      }
    }
    store.put(iditer, neighbors);
    return ret;
  }

  @Override
  protected Logging getLogger() {
    return LOG;
  }
  
  @Override
  public void logStatistics() {
    double size = (double) relation.size();
    if (LOG.isStatistics()){
      LOG.statistics(new DoubleStatistic("Scan rate",counter_all/(size*(size-1.0)/2.0)));
    }
  }

  @Override
  public String getLongName() {
    return "NNDescent kNN";
  }

  @Override
  public String getShortName() {
    return "nn-descent-knn";
  }
  
  @Override
  public KNNQuery<O> getKNNQuery(DistanceQuery<O> distanceQuery, Object... hints) {
    for(Object hint : hints) {
      if(DatabaseQuery.HINT_EXACT.equals(hint)) {
        return null;
      }
    }
    return super.getKNNQuery(distanceQuery, hints);
  }
  
  public static class Factory<O> extends AbstractMaterializeKNNPreprocessor.Factory<O> {
    /**
     * Random generator
     */
    private final RandomFactory rnd;

    /**
     * early termination parameter
     */
    private final double delta;
    
    /**
     * sample rate
     */
    private final double rho;
    
    /**
     * Constructor.
     *
     * @param k K
     * @param distanceFunction distance function
     * @param rnd Random generator
     */
    public Factory(int k, DistanceFunction<? super O> distanceFunction, RandomFactory rnd, double delta, double rho) {
      super(k, distanceFunction);
      this.rnd = rnd;
      this.delta = delta;
      this.rho = rho;
    }

    @Override
    public KNNGraph<O> instantiate(Relation<O> relation) {
      return new KNNGraph<>(relation, distanceFunction, k, rnd, delta, rho);
    }

    /**
     * Parameterization class
     *
     * @author Erich Schubert
     *
     * @apiviz.exclude
     *
     * @param <O> Object type
     */
    public static class Parameterizer<O> extends AbstractMaterializeKNNPreprocessor.Factory.Parameterizer<O> {
      /**
       * Random number generator seed.
       *
       * <p>
       * Key: {@code -knngraph.seed}
       * </p>
       */
      public static final OptionID SEED_ID = new OptionID("knngraph.seed", "The random number seed.");
      
      /**
       * Early termination parameter.
       */
      public static final OptionID DELTA_ID = new OptionID("knngraph.delta", "The early termination parameter.");
      
      /**
       * Sample rate.
       */
      public static final OptionID RHO_ID = new OptionID("knngraph.rho", "The sample rate parameter");
      
      /**
       * Random generator
       */
      private RandomFactory rnd;
      
      /**
       * early termination parameter
       */
      private double delta;
      
      /**
       * sample rate
       */
      private double rho;
      
      @Override
      protected void makeOptions(Parameterization config) {
        super.makeOptions(config);
        RandomParameter rndP = new RandomParameter(SEED_ID);
        if(config.grab(rndP)) {
          rnd = rndP.getValue();
        }
        DoubleParameter deltaP = new DoubleParameter(DELTA_ID, 0.001);
        if(config.grab(deltaP)) {
          delta = deltaP.getValue();
        }
        DoubleParameter rhoP = new DoubleParameter(RHO_ID, 1);
        if (config.grab(rhoP)){
          rho = rhoP.getValue();
        }
      }

      @Override
      protected KNNGraph.Factory<O> makeInstance() {
        return new KNNGraph.Factory<>(k, distanceFunction, rnd, delta, rho);
      }
    }
  }
}


