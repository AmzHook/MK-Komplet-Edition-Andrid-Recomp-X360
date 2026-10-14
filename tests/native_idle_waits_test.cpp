// Optional host integration test of the SDK's actual POSIX wait APIs.
// Compile together with threading_posix.cpp using tools/test_idle_waits.sh.
// No guest code runs. CPU measurements describe this host, not Android heat.
#include <rex/thread.h>

#include <cassert>
#include <chrono>
#include <ctime>
#include <iostream>
#include <thread>

using namespace rex::thread;
using namespace std::chrono_literals;
using Clock = std::chrono::steady_clock;

static double ThreadCpuMs() {
  timespec value{};
  assert(clock_gettime(CLOCK_THREAD_CPUTIME_ID, &value) == 0);
  return value.tv_sec * 1000.0 + value.tv_nsec / 1000000.0;
}

int main() {
  auto event = Event::CreateAutoResetEvent(false);
  auto semaphore = Semaphore::Create(0, 3);
  assert(event && semaphore);
  WaitHandle* handles[] = {event.get(), semaphore.get()};

  // Empty zero-time waits and auto-reset event consumption.
  assert(WaitAny(handles, 2, false, 0ms).first == WaitResult::kTimeout);
  event->Set();
  auto first = WaitAny(handles, 2, false, 0ms);
  assert(first.first == WaitResult::kSuccess && first.second == 0);
  assert(WaitAny(handles, 2, false, 0ms).first == WaitResult::kTimeout);

  // A counted semaphore releases exactly as many waits as its count.
  assert(semaphore->Release(2, nullptr));
  auto second = WaitAny(handles, 2, false, 0ms);
  assert(second.first == WaitResult::kSuccess && second.second == 1);
  assert(WaitAny(handles, 2, false, 0ms).first == WaitResult::kSuccess);
  assert(WaitAny(handles, 2, false, 0ms).first == WaitResult::kTimeout);

  // WaitAll consumes both objects only once.
  event->Set();
  assert(semaphore->Release(1, nullptr));
  assert(WaitAll(handles, 2, false, 0ms) == WaitResult::kSuccess);
  assert(WaitAny(handles, 2, false, 0ms).first == WaitResult::kTimeout);

  const auto timeout_start = Clock::now();
  assert(WaitAny(handles, 2, false, 15ms).first == WaitResult::kTimeout);
  assert(Clock::now() - timeout_start >= 15ms);

  // A real event interrupts a long wait; do not assert a scheduler-dependent
  // latency threshold. The wait must return success before its own timeout.
  std::thread wake([&] {
    std::this_thread::sleep_for(20ms);
    event->Set();
  });
  auto woken = WaitAny(handles, 2, false, 1s);
  wake.join();
  assert(woken.first == WaitResult::kSuccess && woken.second == 0);

  auto current = Thread::GetCurrentThread();
  assert(current);
  int callbacks = 0;
  current->QueueUserCallback([&] { ++callbacks; });
  auto apc = WaitAny(handles, 2, true, 100ms);
  assert(apc.first == WaitResult::kUserCallback && callbacks == 1);

  // Delivery from another thread must also interrupt an alertable wait.
  std::thread apc_sender([&] {
    std::this_thread::sleep_for(20ms);
    current->QueueUserCallback([&] { ++callbacks; });
  });
  apc = WaitAny(handles, 2, true, 1s);
  apc_sender.join();
  assert(apc.first == WaitResult::kUserCallback && callbacks == 2);

  const auto cpu_before = ThreadCpuMs();
  const auto idle_start = Clock::now();
  for (int sample = 0; sample < 2; ++sample) {
    assert(WaitAny(handles, 2, true, 250ms).first == WaitResult::kTimeout);
  }
  const auto idle_cpu_ms = ThreadCpuMs() - cpu_before;
  const auto idle_wall_ms =
      std::chrono::duration<double, std::milli>(Clock::now() - idle_start).count();
  assert(idle_wall_ms >= 500.0);
  // Report CPU usage without a load-dependent pass/fail performance threshold.
  std::cout << "Public WaitAny/WaitAll behavior passed: zero timeout, auto-reset, "
               "semaphore count, all-consumption, finite timeout, event wake, "
               "queued and concurrent APC delivery\n";
  std::cout << "{\"idle_wall_ms\":" << idle_wall_ms
            << ",\"idle_thread_cpu_ms\":" << idle_cpu_ms
            << ",\"idle_cpu_fraction\":" << idle_cpu_ms / idle_wall_ms << "}\n";
}
