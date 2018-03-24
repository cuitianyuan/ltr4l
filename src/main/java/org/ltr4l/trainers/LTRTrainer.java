/*
 * Copyright 2018 org.LTR4L
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ltr4l.trainers;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ltr4l.Ranker;
import org.ltr4l.query.Document;
import org.ltr4l.query.Query;
import org.ltr4l.query.QuerySet;
import org.ltr4l.tools.Config;
import org.ltr4l.tools.Error;
import org.ltr4l.tools.RankEval;
import org.ltr4l.tools.Report;

/**
 * Abstract class used for training the model held by Rankers.
 * This class is also the parameter holder.
 *
 * train() must be implemented based on algorithm used.
 */
public abstract class LTRTrainer<R extends Ranker, C extends Config> implements Trainer {
  protected final int epochNum;
  protected final List<Query> trainingSet;
  protected final List<Query> validationSet;
  protected double maxScore;
  protected final Report report;
  protected final R ranker;
  protected final C config;
  protected final Error errorFunc;
  protected final int batchSize;
  protected final int ndcgK;
  protected final String modelFile;

  LTRTrainer(QuerySet training, QuerySet validation, Reader reader) {
    this.config = getConfig(reader);
    epochNum = config.numIterations;
    trainingSet = training.getQueries();
    validationSet = validation.getQueries();
    maxScore = 0d;
    ranker = constructRanker();
    assert(config.batchSize >= 0);
    batchSize = config.batchSize;
    ndcgK  = getNdcgAtK(config);
    modelFile = getModelFile(config);
    this.report = Report.getReport();  // TODO: use default Report for now...
    this.errorFunc = makeErrorFunc();
  }

  private static int getNdcgAtK(Config config){
    final int K_DEFAULT = 10;
    if(config.evaluation == null || config.evaluation.params == null) return K_DEFAULT;
    return Config.getInt(config.evaluation.params, "k", K_DEFAULT);   // TODO: allow users to specify another evaluator
  }

  private static String getModelFile(Config config){
    if(config.model == null || config.model.file == null || config.model.file.isEmpty())
      return Config.Model.DEFAULT_MODEL_FILE;
    return config.model.file;
  }

  abstract double calculateLoss(List<Query> queries);

  /**
   * This method is used to assign errorFunc.
   * Child classes must specify which error they will use.
   * @return Implementation of Error
   */
  protected abstract Error makeErrorFunc();

  @Override
  public double[] calculateLoss() {
    return new double[]{calculateLoss(trainingSet), calculateLoss(validationSet)};
  }

  @Override
  public void validate(int iter, int pos) {
    double newScore = RankEval.ndcgAvg(this, validationSet, pos);
    if (newScore > maxScore) {
      maxScore = newScore;
    }
    double[] losses = calculateLoss();
    report.log(iter, newScore, losses[0], losses[1]);
  }

  @Override
  public void trainAndValidate() {
    for (int i = 1; i <= epochNum; i++) {
      train();
      validate(i, ndcgK);
    }
    report.close();
    try {
      ranker.writeModel(config, modelFile);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected abstract <R extends Ranker> R constructRanker();

  public abstract <C extends Config> Class<C> getConfigClass();

  <C extends Config> C getConfig(Reader reader){
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.readValue(reader, getConfigClass());
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /**
   * Sorts the associated documents in a  query according to the ranker's model via predict method, from highest score to lowest.
   * For example, if a query has the following associated document list:
   * {(2)docA, (3)docB, (1)docC}
   * where the numbers in the parentheses are predicted scores,
   * sortP will return the following new list:
   * {docB, docA, docC}
   *
   * A new list is made in order to preserve the order of the original document list.
   *
   * sortP is currently also used to calculate NDCG, and thus a new sorted list should be used to avoid calculation errors.
   *
   * @param query
   * @return new sorted document list.
   */
  @Override
  public List<Document> sortP(Query query){
    List<Document> ranks = new ArrayList<>(query.getDocList());
    ranks.sort((docA, docB) -> Double.compare(ranker.predict(docB.getFeatures()), ranker.predict(docA.getFeatures()))); //reversed for high to low.
    return ranks;
  }

}
