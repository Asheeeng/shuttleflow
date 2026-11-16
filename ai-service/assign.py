"""
多车协同分配: 到站拆5原子事件, 可用座位=容量-下客后人数, 下游预留上限40%
支持OR-Tools CP-SAT整数规划求解, 降级为启发式规则
"""
import numpy as np
import logging

logger = logging.getLogger(__name__)

try:
    from ortools.sat.python import cp_model
    HAS_ORTOOLS = True
    logger.info("OR-Tools CP-SAT已加载")
except ImportError:
    HAS_ORTOOLS = False
    logger.warning("OR-Tools未安装,降级为启发式规则分配")

class MultiVehicleAllocator:
    def assign(self, vehicles, waiting, station_id, line_id, direction, strategy="seat_release_reserve"):
        if not vehicles:
            return {"strategy": "no_vehicle", "allocation": [], "leftover": waiting, "message": "无车辆到站"}

        results = []
        for v in vehicles:
            cap = v.get("capacity", 13)
            load = v.get("currentLoad", 0)
            if strategy == "original":
                available = max(0, cap - load)
                alight_est = 0
            else:
                alight_est = int(round(load * 0.3)) if load > 0 else 0
                available = max(0, cap - (load - alight_est))
            if strategy == "seat_release_reserve":
                reserve = min(int(available * 0.4), available)
                if waiting > available * len(vehicles):
                    reserve = 0
            else:
                reserve = 0
            assignable = available - reserve
            results.append({
                "vehicleId": v.get("vehicleId"),
                "plateNo": v.get("plateNo"),
                "capacity": cap,
                "currentLoad": load,
                "alightEstimate": alight_est,
                "availableAfterAlight": available,
                "downstreamReserve": reserve,
                "assignable": assignable
            })
        results.sort(key=lambda x: x["assignable"], reverse=True)

        if HAS_ORTOOLS and strategy == "seat_release_reserve" and len(results) > 1:
            allocation = self._assign_cpsat(results, waiting, station_id, line_id, direction)
        else:
            allocation = self._assign_heuristic(results, waiting)

        total_available = sum(r["availableAfterAlight"] for r in results)
        total_assignable = sum(r["assignable"] for r in results)
        fcfs_available = sum(max(0, r["capacity"] - r["currentLoad"]) for r in results)
        remaining = waiting - sum(a["boardCount"] for a in allocation)

        solver_used = "OR-Tools CP-SAT" if (HAS_ORTOOLS and strategy == "seat_release_reserve" and len(results) > 1) else "heuristic"
        return {
            "strategy": strategy,
            "solver": solver_used,
            "vehicles": results,
            "allocation": allocation,
            "leftover": remaining,
            "totalAvailableSeats": total_available,
            "totalAssignableSeats": total_assignable,
            "counterfactual": f"若忽略下客释放仅{fcfs_available}座可用;考虑下客释放后{total_available}座可用",
            "reasonCode": "MULTI_VEHICLE_ASSIGN",
            "confidence": 0.85
        }

    def _assign_cpsat(self, results, waiting, station_id, line_id, direction):
        """OR-Tools CP-SAT整数规划求解多车分配"""
        n = len(results)
        model = cp_model.CpModel()

        board = [model.NewIntVar(0, results[i]["assignable"], f"board_{i}") for i in range(n)]
        total_board = model.NewIntVar(0, waiting, "total_board")
        model.Add(total_board == sum(board))

        fairness_bound = model.NewIntVar(0, max(r["assignable"] for r in results), "fairness_bound")
        for i in range(n):
            for j in range(i + 1, n):
                diff = model.NewIntVar(0, max(r["assignable"] for r in results), f"diff_{i}_{j}")
                model.Add(diff >= board[i] - board[j])
                model.Add(diff >= board[j] - board[i])
                model.Add(fairness_bound >= diff)

        model.Maximize(total_board * 100 - fairness_bound)

        solver = cp_model.CpSolver()
        solver.parameters.max_time_in_seconds = 1.0
        status = solver.Solve(model)

        allocation = []
        if status in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            for i in range(n):
                board_count = int(solver.Value(board[i]))
                if board_count > 0:
                    allocation.append({
                        "vehicleId": results[i]["vehicleId"],
                        "plateNo": results[i]["plateNo"],
                        "boardCount": board_count,
                        "reserveForDownstream": results[i]["downstreamReserve"],
                        "reasonCode": "CP_SAT_OPTIMAL" if status == cp_model.OPTIMAL else "CP_SAT_FEASIBLE",
                        "confidence": 0.90,
                        "suggestion": f"CP-SAT:车辆{results[i]['plateNo']}上{board_count}人,给下游预留{results[i]['downstreamReserve']}座"
                    })
            logger.info(f"CP-SAT求解完成: status={solver.StatusName(status)}, objective={solver.ObjectiveValue()}")
        else:
            logger.warning(f"CP-SAT求解失败(status={solver.StatusName(status)}),降级为启发式")
            allocation = self._assign_heuristic(results, waiting)

        return allocation

    def _assign_heuristic(self, results, waiting):
        """启发式规则分配(降级方案)"""
        allocation = []
        remaining = waiting
        for vs in results:
            board = min(remaining, vs["assignable"])
            if board > 0:
                allocation.append({
                    "vehicleId": vs["vehicleId"],
                    "plateNo": vs["plateNo"],
                    "boardCount": board,
                    "reserveForDownstream": vs["downstreamReserve"],
                    "reasonCode": "SEAT_RELEASE",
                    "confidence": 0.85,
                    "suggestion": f"车辆{vs['plateNo']}上{board}人,给下游预留{vs['downstreamReserve']}座"
                })
                remaining -= board
        return allocation