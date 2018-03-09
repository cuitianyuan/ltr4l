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

package org.ltr4l.nn;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.ltr4l.query.Document;
import org.ltr4l.tools.Error;
import org.ltr4l.tools.Regularization;

/**
 * MLP is a Ranker which uses a Multi-Layer Perceptron network.
 * The network is a feed-forward network, and the links between
 * nodes are represented by the Edge class.
 * Nodes contain lists of edges (source and input), and edges contain
 * information about source nodes and destination nodes.
 *
 * MLPRankers should have methods for forward propagation, backpropagation, and updating of weights.
 *
 */
public class MLP extends AbstractMLP<MLP.Node, MLP.Edge> {

  /**
   * The network is constructed within the constructor of MLP.
   * Bias edges are created for each node, which will add some constant to the total input to the node.
   * The weights held by these edges are initialized with constants (regardless of weight initialization strategy).
   * @param inputDim       The number of nodes in the input layer; the dimension of the feature space.
   * @param networkShape    Contains information about the number of hidden layers, the number of nodes in each layer,
   *                        and the activation of the nodes in the layer.
   * @param optFact         Contains information about which optimizer to use for weight updating.
   * @param regularization  Contains information about what regularization to use for weight updating.
   * @param weightModel     How to initialize weights (i.e. randomly, gaussian, etc...)
   */
  public MLP(int inputDim, NetworkShape networkShape, Optimizer.OptimizerFactory optFact, Regularization regularization, String weightModel) {
    super(inputDim, networkShape, optFact, regularization, weightModel);
  }

  protected void addOutputs(NetworkShape ns){
    return; //Default is do not specify... make abstract?
  }

  @Override
  protected Node constructNode(Activation activation) {
    return new Node(activation);
  }

  @Override
  protected Edge constructEdge(Node source, Node destination, Optimizer opt, double weight) {
    return new Edge(source, destination, opt, weight);
  }

  //This is for one output node.
  public void backProp(double target, Error errorFunc) {
    Node outputNode = network.get(network.size() - 1).get(0);
    double output = outputNode.getOutput();
    //First, get the derivative ∂C/∂O and set it to output derivative of the final node.
    double der = errorFunc.der(output, target);
    outputNode.setOutputDer(der);

    for (int layerIdx = network.size() - 1; layerIdx >= 1; layerIdx--) { //When going through each layer, you modify the previous layer.
      List<Node> layer = network.get(layerIdx);

      for (Node node : layer) {
        // Second, find ∂C/∂I by (∂C/∂O)(∂O/∂I)
        // I = total Input; O = output = Activation(I)
        double totalInput = node.getTotalInput();
        double inDer = node.getActivation().derivative(totalInput) * node.getOutputDer();
        node.setInputDer(inDer);

        //First edge is bias.
        Edge edge = node.getInputEdges().get(0);

        double accErrDer = edge.getAccErrorDer();
        accErrDer += node.getInputDer();
        edge.setAccErrorDer(accErrDer);

        for (int edgeNum = 1; edgeNum < node.getInputEdges().size(); edgeNum++) {
          edge = node.getInputEdges().get(edgeNum);
          if (!edge.isDead()) {
            //(∂C/∂I)*(∂I/∂w) = Σ∂C/∂Ii *(∂Ii/∂w) = ∂C/∂w
            //(∂Ii/∂w) = Oi, because Oi*wi = Ii
            double errorDer = node.getInputDer() * edge.getSource().getOutput(); //(∂C/∂I)*(∂I/∂w)
            accErrDer = edge.getAccErrorDer();
            accErrDer += errorDer;
            edge.setAccErrorDer(accErrDer);
          }
        }
      }
      if (layerIdx != 1) {
        List<Node> previousLayer = network.get(layerIdx - 1);
        for (Node node : previousLayer) {
          double oder = 0;
          for (Edge outEdge : node.getOutputEdges()) {
            //∂C/∂Oi = ∂Ik/∂Oi * ∂C/∂Ik
            oder += outEdge.getWeight() * outEdge.getDestination().getInputDer();
          }
          node.setOutputDer(oder);
        }
      }
    }
    numAccumulatedDer += 1;
  }

  //This is for the case of multiple output layers.
  public void backProp(double[] targets, Error errorFunc) {
    //First, feed derivative into each node in output layer
    //Skip the first node, as the derivative will be set through backprop method.
    List<Node> outputLayer = network.get(network.size() - 1);
    for (int i = 1; i < outputLayer.size(); i++) {
      Node outputNode = outputLayer.get(i);
      double output = outputNode.getOutput();
      double der = errorFunc.der(output, targets[i]);
      outputNode.setOutputDer(der);
    }
    //Then conduct backpropagation as usual.
    backProp(targets[0], errorFunc);

  }

  //Note: regularization not yet implemented.
  public void updateWeights(double lrRate, double rgRate) {
    //Update all weights in all edges.
    for (int layerId = 1; layerId < network.size(); layerId++) {  //All Layers
      List<Node> layer = network.get(layerId);
      for (Node node : layer) {                     //All Nodes
        for (Edge edge : node.getInputEdges()) { //All edges for each node.
          if (!edge.isDead()) {
            double rgDer = 0;
            if (regularization != null)
              rgDer = regularization.derivative(edge.getWeight());

            if (numAccumulatedDer > 0) {
              //"Optimize" dw; ηdw
              double cost = edge.getOptimizer().optimize(edge.getAccErrorDer(), lrRate, iter);
              double weight = edge.getWeight();
              weight += cost / numAccumulatedDer; //accumulated many dw, so avg that.

              //Wi = Wi + (learning rate/optimization) * ∂C/∂w
              //Further update weight based on regularization.
              double newWeight = weight - rgDer * (lrRate * rgRate);
              if (regularization instanceof Regularization.L1 && weight * newWeight < 0) {
                edge.setWeight(0d);
                edge.setDead(true);
              } else {
                edge.setWeight(newWeight);
              }
              //Wi = Wi + (learning rate/optimization) * ∂C/∂w
              edge.setAccErrorDer(0); //After the change has been applied, reset the accumulated derivatives.
            }
          }
        }
      }
    }
    numAccumulatedDer = 0; //Weights updated, now no derivatives have been accumulated.
    iter++;
  }

  /**
   * Edge (link) in the network.
   * Holds information about which nodes are connected, the weight between the nodes, and dw.
   */
  protected static class Edge extends AbstractEdge<Node> { //Serializable?
    private double accErrorDer;
    private boolean isDead;

    Edge(Node source, Node destination, Optimizer optimizer, double weight) {
      super(source, destination, optimizer, weight);
      accErrorDer = 0.0;
      isDead = false;
    }
    public boolean isDead() {
      return isDead;
    }

    public void setDead(boolean bool) {
      isDead = bool;
    }

    public void setAccErrorDer(double accErrorDer) {
      this.accErrorDer = accErrorDer;
    }

    public double getAccErrorDer() {
      return accErrorDer;
    }

  }

  /**
   * Defines what type of AbstractEdge the node will hold.
   */
  protected static class Node extends AbstractNode<Edge> {
    Node(Activation activation){
      super(activation);
    }
  }
}
