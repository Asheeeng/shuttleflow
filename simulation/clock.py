"""可暂停的虚拟运行时钟；仿真事件时间与墙上时钟分离。"""
from __future__ import annotations

import threading
import time


class SimulationStopped(RuntimeError):
    pass


class SimulationClock:
    def __init__(self, speed: float = 60.0):
        if speed <= 0:
            raise ValueError("speed必须大于0")
        self.speed = float(speed)
        self._condition = threading.Condition()
        self._virtual_base = 0.0
        self._wall_origin = time.monotonic()
        self._paused = False
        self._stopped = False

    def now(self) -> float:
        with self._condition:
            if self._paused or self._stopped:
                return self._virtual_base
            return self._virtual_base + (time.monotonic() - self._wall_origin) * self.speed

    def pause(self) -> None:
        with self._condition:
            if not self._paused and not self._stopped:
                self._virtual_base += (time.monotonic() - self._wall_origin) * self.speed
                self._paused = True
                self._condition.notify_all()

    def resume(self) -> None:
        with self._condition:
            if self._paused and not self._stopped:
                self._wall_origin = time.monotonic()
                self._paused = False
                self._condition.notify_all()

    def set_speed(self, speed: float) -> None:
        if speed <= 0:
            raise ValueError("speed必须大于0")
        with self._condition:
            if not self._paused and not self._stopped:
                self._virtual_base += (time.monotonic() - self._wall_origin) * self.speed
                self._wall_origin = time.monotonic()
            self.speed = float(speed)
            self._condition.notify_all()

    def stop(self) -> None:
        with self._condition:
            self._stopped = True
            self._condition.notify_all()

    @property
    def paused(self) -> bool:
        with self._condition:
            return self._paused

    @property
    def stopped(self) -> bool:
        with self._condition:
            return self._stopped

    def wait_until(self, target_seconds: float) -> float:
        """等到虚拟时间达到目标；暂停可唤醒等待，停止会抛SimulationStopped。"""
        with self._condition:
            while True:
                if self._stopped:
                    raise SimulationStopped("仿真已停止")
                if self._paused:
                    self._condition.wait()
                    continue
                now = self._virtual_base + (time.monotonic() - self._wall_origin) * self.speed
                remaining = target_seconds - now
                if remaining <= 0:
                    return now
                self._condition.wait(timeout=remaining / self.speed)
