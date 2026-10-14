#include "mk_file_trace.h"

#include <atomic>
#include <cstdlib>
#include <iostream>
#include <thread>
#include <vector>

namespace {
void Require(bool condition, const char* message) {
  if (!condition) {
    std::cerr << message << '\n';
    std::exit(1);
  }
}
}

int main() {
  using mk::diagnostics::FileTraceBudget;
  using mk::diagnostics::IsMoviePath;
  for (auto path : {"game:\\Movies\\Logo.xbmovie", "D:/mOvIeS/Logo", "logo.WMV", "logo.XBMOVIE"}) {
    Require(IsMoviePath(path), "Movie requests must be recognized independent of path case");
  }
  for (auto path : {"", "game:\\MoviesExtra\\config.ini", "NotMovies/config.ini", "config.wmv.ini"}) {
    Require(!IsMoviePath(path), "Other asset names must not consume the movie budget");
  }

  FileTraceBudget budget;
  for (int i = 0; i < 128; ++i) {
    const auto d = budget.Observe("Asset/file.bin");
    Require(d.log_path && !d.startup_limit, "The initial 128 file requests must be traced");
  }
  auto d = budget.Observe("Asset/file.bin");
  Require(!d.log_path && d.startup_limit, "The startup limit must be reported once");
  for (int i = 0; i < 1000; ++i) {
    d = budget.Observe("Asset/file.bin");
    Require(!d.log_path && !d.startup_limit, "Later non-movie requests must remain bounded");
  }
  for (int i = 0; i < 64; ++i) {
    d = budget.Observe("game:\\Movies\\Logo.xbmovie");
    Require(d.log_path && d.movie && !d.movie_limit, "An additional 64 movie requests must remain visible");
  }
  d = budget.Observe("game:\\Movies\\Logo.xbmovie");
  Require(!d.log_path && d.movie_limit, "The additional movie limit must be reported once");
  for (int i = 0; i < 1000; ++i) {
    d = budget.Observe("game:\\Movies\\Logo.xbmovie");
    Require(!d.log_path && !d.movie_limit, "Repeated movie requests must not flood the log");
  }

  FileTraceBudget concurrent;
  std::atomic<unsigned> paths{0}, startup_limits{0}, movie_limits{0};
  std::vector<std::thread> threads;
  for (int n = 0; n < 8; ++n) {
    threads.emplace_back([&] {
      for (int i = 0; i < 10000; ++i) {
        const auto result = concurrent.Observe(i % 2 ? "Movies/intro.xbmovie" : "Asset/file.bin");
        paths.fetch_add(result.log_path, std::memory_order_relaxed);
        startup_limits.fetch_add(result.startup_limit, std::memory_order_relaxed);
        movie_limits.fetch_add(result.movie_limit, std::memory_order_relaxed);
      }
    });
  }
  for (auto& thread : threads) thread.join();
  Require(paths == 192, "Concurrent guest threads must share the same bounded trace budget");
  Require(startup_limits == 1 && movie_limits == 1, "Concurrent limit notices must occur exactly once");
  std::cout << "Native file trace checks passed (classification, bounded output, concurrent guest threads)\n";
}
