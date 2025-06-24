package org.sbpo2025.challenge;

import com.google.ortools.Loader;
import com.google.ortools.linearsolver.MPConstraint;
import com.google.ortools.linearsolver.MPObjective;
import com.google.ortools.linearsolver.MPSolver;
import com.google.ortools.linearsolver.MPVariable;
import org.apache.commons.lang3.time.StopWatch;

import java.util.*;
import java.util.concurrent.TimeUnit;

public class ChallengeSolver {
    private final long MAX_RUNTIME = 600000; // milliseconds; 10 minutes

    protected List<Map<Integer, Integer>> orders;
    protected List<Map<Integer, Integer>> aisles;
    protected int nItems;
    protected int waveSizeLB;
    protected int waveSizeUB;

    public ChallengeSolver(
        List<Map<Integer, Integer>> orders, List<Map<Integer, Integer>> aisles, int nItems, int waveSizeLB, int waveSizeUB) {
        this.orders = orders;
        this.aisles = aisles;
        this.nItems = nItems;
        this.waveSizeLB = waveSizeLB;
        this.waveSizeUB = waveSizeUB;
    }

    public ChallengeSolution solve(StopWatch stopWatch) {
        // Implement your solution here
        PartialResult bestSolution = new PartialResult(null, 0);
        for (int k = 1; k < aisles.size(); k++) {
            System.out.println("Solving for k = " + k);
            PartialResult partialResult = problem1a(k);
            if (partialResult == null) {
                System.err.println("No solution found for k = " + k);
                continue;
            }
            double delta = partialResult.objValue() - (double) waveSizeUB / k;
            if (delta <= 0) {
              delta = -delta;
            }
            System.out.println("Delta: " + delta);
            if (delta <= 0.00000001) { // found upper bound
                System.out.println("Stopping early due to optimal solution.");
                break;
            }
            if (partialResult.objValue() > bestSolution.objValue()) {
                System.out.println("Found a better solution for k = " + k + " with objective value: " + partialResult.objValue());
                bestSolution = partialResult;
            }
        }
        System.out.println("Best solution found with objective value: " + bestSolution.objValue());
        return bestSolution.partialSolution();
    }


    /**
     * Problem 1.a: Solve the problem assuming number of selected aisles is constant
     * @return the solution to the problem
     */
    protected PartialResult problem1a(int k) {
        // Solver
        Loader.loadNativeLibraries();
        MPSolver solver = MPSolver.createSolver("SCIP");
        if (solver == null) {
            System.out.println("Could not create solver SCIP");
            return null;
        }
        // solver.setNumThreads(8);

        // Variables
        int nOrders = orders.size();
        List<MPVariable> selected_orders = getVariablesOrders(solver, nOrders);
        int nAisles = aisles.size();
        List<MPVariable> selected_aisles = getVariablesAisles(solver, nAisles);

        // Unique sub problem constraint
        MPConstraint have_k_aisles = solver.makeConstraint(k, k, "Allow K aisles");
        for (MPVariable x : selected_orders) {
            have_k_aisles.setCoefficient(x, 0);
        }
        for (MPVariable y : selected_aisles) {
            have_k_aisles.setCoefficient(y, 1);
        }

        // General problem constraints
        makeWaveBoundsConstraint(solver, nOrders, selected_orders, selected_aisles);
        makeAvailableCapacityConstraint(solver, nOrders, selected_orders, nAisles, selected_aisles);

        // Objective
        MPObjective objective = solver.objective();
        for (int o = 0; o < nOrders; o++) {
            Map<Integer, Integer> order = orders.get(o);
            int coeff = 0;
            Collection<Integer> quantities = order.values();
            for (Integer quantity: quantities) {
                coeff += quantity;
            }
            MPVariable x = selected_orders.get(o);
            objective.setCoefficient(x, coeff);
        }
        for (MPVariable y : selected_aisles) {
            objective.setCoefficient(y, 0);
        }
        objective.setMaximization();

        return calculatePartialResult(solver, objective, nOrders, selected_orders, nAisles, selected_aisles, k);
    }

    protected List<MPVariable> getVariablesOrders(MPSolver solver, int nOrders) {
        ArrayList<MPVariable> selected_orders = new ArrayList<>(nOrders);
        for (int i = 0; i < nOrders; i++) {
            selected_orders.add(solver.makeBoolVar("order_" + i));
        }
        return selected_orders;
    }

    protected List<MPVariable> getVariablesAisles(MPSolver solver, int nAisles) {
        ArrayList<MPVariable> selected_aisles = new ArrayList<>(nAisles);
        for (int i = 0; i < nAisles; i++) {
            selected_aisles.add(solver.makeBoolVar("aisle_" + i));
        }
        return selected_aisles;
    }

    protected void makeWaveBoundsConstraint(MPSolver solver, int nOrders, List<MPVariable> selected_orders, List<MPVariable> selected_aisles) {
        MPConstraint wave_bounds = solver.makeConstraint(waveSizeLB, waveSizeUB, "Wave size bounds");
        for (int o = 0; o < nOrders; o++) {
            Map<Integer, Integer> order = orders.get(o);
            int coeff = 0;
            Collection<Integer> quantities = order.values();
            for (Integer quantity: quantities) {
                coeff += quantity;
            }
            MPVariable x = selected_orders.get(o);
            wave_bounds.setCoefficient(x, coeff);
        }
        for (MPVariable y : selected_aisles) {
            wave_bounds.setCoefficient(y, 0);
        }
    }

    protected void makeAvailableCapacityConstraint(MPSolver solver, int nOrders, List<MPVariable> selected_orders, int nAisles, List<MPVariable> selected_aisles) {
        double infinity = Double.POSITIVE_INFINITY;
        Set<Integer> item_keys = new HashSet<>(Collections.emptySet());
        for (Map<Integer, Integer> order : orders) {
            item_keys.addAll(order.keySet());
        }
        for (Map<Integer, Integer> aisle : aisles) {
            item_keys.addAll(aisle.keySet());
        }

        for (Integer i : item_keys) {
            MPConstraint available_capacity = solver.makeConstraint(-infinity, 0, "Make sure items in orders are available in aisles");
            for (int o = 0; o < nOrders; o++) {
                MPVariable x = selected_orders.get(o);
                Integer coeff = orders.get(o).get(i);
                if (coeff == null) coeff = 0;
                available_capacity.setCoefficient(x, coeff);
            }
            for (int a = 0; a < nAisles; a++) {
                MPVariable y = selected_aisles.get(a);
                Integer coeff = aisles.get(a).get(i);
                if (coeff == null) coeff = 0;
                available_capacity.setCoefficient(y, -coeff);
            }
        }
    }

    protected PartialResult calculatePartialResult(MPSolver solver, MPObjective objective, int nOrders, List<MPVariable> selected_orders, int nAisles, List<MPVariable> selected_aisles, int k) {
        final MPSolver.ResultStatus resultStatus = solver.solve();

        Set<Integer> finalOrders = new HashSet<>();
        Set<Integer> finalAisles = new HashSet<>();
        if (resultStatus == MPSolver.ResultStatus.OPTIMAL) {

            for (int i = 0; i < nOrders; i++) {
                MPVariable x = selected_orders.get(i);
                if (x.solutionValue() == 1) {
                    finalOrders.add(i);
                }
            }

            for (int i = 0; i < nAisles; i++) {
                MPVariable y = selected_aisles.get(i);
                if (y.solutionValue() == 1) {
                    finalAisles.add(i);
                }
            }

            ChallengeSolution partialSolution = new ChallengeSolution(finalOrders, finalAisles);
            return new PartialResult(partialSolution, objective.value() / k);
        } else {
            return null;
        }
    }

    /*
     * Get the remaining time in seconds
     */
    protected long getRemainingTime(StopWatch stopWatch) {
        return Math.max(
                TimeUnit.SECONDS.convert(MAX_RUNTIME - stopWatch.getTime(TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS),
                0);
    }

    protected boolean isSolutionFeasible(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return false;
        }

        int[] totalUnitsPicked = new int[nItems];
        int[] totalUnitsAvailable = new int[nItems];

        // Calculate total units picked
        for (int order : selectedOrders) {
            for (Map.Entry<Integer, Integer> entry : orders.get(order).entrySet()) {
                totalUnitsPicked[entry.getKey()] += entry.getValue();
            }
        }

        // Calculate total units available
        for (int aisle : visitedAisles) {
            for (Map.Entry<Integer, Integer> entry : aisles.get(aisle).entrySet()) {
                totalUnitsAvailable[entry.getKey()] += entry.getValue();
            }
        }

        // Check if the total units picked are within bounds
        int totalUnits = Arrays.stream(totalUnitsPicked).sum();
        if (totalUnits < waveSizeLB || totalUnits > waveSizeUB) {
            return false;
        }

        // Check if the units picked do not exceed the units available
        for (int i = 0; i < nItems; i++) {
            if (totalUnitsPicked[i] > totalUnitsAvailable[i]) {
                return false;
            }
        }

        return true;
    }

    protected double computeObjectiveFunction(ChallengeSolution challengeSolution) {
        Set<Integer> selectedOrders = challengeSolution.orders();
        Set<Integer> visitedAisles = challengeSolution.aisles();
        if (selectedOrders == null || visitedAisles == null || selectedOrders.isEmpty() || visitedAisles.isEmpty()) {
            return 0.0;
        }
        int totalUnitsPicked = 0;

        // Calculate total units picked
        for (int order : selectedOrders) {
            totalUnitsPicked += orders.get(order).values().stream()
                    .mapToInt(Integer::intValue)
                    .sum();
        }

        // Calculate the number of visited aisles
        int numVisitedAisles = visitedAisles.size();

        // Objective function: total units picked / number of visited aisles
        return (double) totalUnitsPicked / numVisitedAisles;
    }
}
