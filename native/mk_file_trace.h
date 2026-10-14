#pragma once

#include <atomic>
#include <initializer_list>
#include <string_view>

namespace mk::diagnostics {

inline bool IsMoviePath(std::string_view path) noexcept {
  const auto lower = [](char c) { return c >= 'A' && c <= 'Z' ? char(c + ('a' - 'A')) : c; };
  const auto equal = [&](std::string_view value, std::string_view expected) {
    if (value.size() != expected.size()) return false;
    for (size_t i = 0; i < value.size(); ++i) {
      if (lower(value[i]) != expected[i]) return false;
    }
    return true;
  };
  size_t start = 0;
  for (size_t end = 0; end <= path.size(); ++end) {
    if (end == path.size() || path[end] == '/' || path[end] == '\\' || path[end] == ':') {
      if (equal(path.substr(start, end - start), "movies")) return true;
      start = end + 1;
    }
  }
  for (std::string_view extension : {".wmv", ".xbmovie"}) {
    if (path.size() >= extension.size() && equal(path.substr(path.size() - extension.size()), extension)) {
      return true;
    }
  }
  return false;
}

struct FileTraceDecision {
  bool log_path = false;
  bool movie = false;
  bool startup_limit = false;
  bool movie_limit = false;
};

// Process-lifetime state, shared by guest threads. The first 128 requests and
// at most 64 subsequent movie requests are traced; counters saturate to avoid
// overflow and each limit is reported once. No borrowed path is retained.
class FileTraceBudget {
 public:
  FileTraceDecision Observe(std::string_view path) noexcept {
    FileTraceDecision result;
    result.movie = IsMoviePath(path);
    const auto startup = Take(startup_, 129);
    result.log_path = startup < 128;
    result.startup_limit = startup == 128;
    if (!result.log_path && result.movie) {
      const auto movie = Take(movies_, 65);
      result.log_path = movie < 64;
      result.movie_limit = movie == 64;
    }
    return result;
  }

 private:
  static unsigned Take(std::atomic<unsigned>& counter, unsigned cap) noexcept {
    auto value = counter.load(std::memory_order_relaxed);
    while (value < cap) {
      if (counter.compare_exchange_weak(value, value + 1, std::memory_order_relaxed)) return value;
    }
    return cap;
  }

  std::atomic<unsigned> startup_{0};
  std::atomic<unsigned> movies_{0};
};

}  // namespace mk::diagnostics
