#pragma once

#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <string>
#include <system_error>
#include <vector>

namespace northstar {

inline void append_spool(const std::string &path, const std::string &body) {
    std::filesystem::path p(path);
    if (p.has_parent_path()) std::filesystem::create_directories(p.parent_path());
    std::ofstream out(path, std::ios::app);
    if (!out) throw std::runtime_error("cannot open telemetry spool");
    out << body << '\n';
    out.flush();
    if (!out) throw std::runtime_error("cannot persist telemetry spool");
}

inline std::vector<std::string> read_spool(const std::string &path) {
    std::ifstream in(path);
    std::vector<std::string> lines;
    if (!in) return lines;
    std::string line;
    while (std::getline(in, line)) if (!line.empty()) lines.push_back(line);
    return lines;
}

inline void replace_spool_atomically(const std::string &path,
                                     const std::vector<std::string> &lines,
                                     std::size_t first) {
    std::filesystem::path target(path);
    if (first >= lines.size()) {
        std::error_code ec;
        std::filesystem::remove(target, ec);
        if (ec) throw std::runtime_error("cannot remove drained telemetry spool: " + ec.message());
        return;
    }

    std::filesystem::path tmp = target;
    tmp += ".tmp";
    {
        std::ofstream out(tmp, std::ios::trunc);
        if (!out) throw std::runtime_error("cannot open telemetry spool temp file");
        for (std::size_t i = first; i < lines.size(); ++i) out << lines[i] << '\n';
        out.flush();
        if (!out) throw std::runtime_error("cannot persist telemetry spool temp file");
    }

    std::error_code ec;
    std::filesystem::rename(tmp, target, ec);
    if (ec) {
        std::filesystem::remove(tmp);
        throw std::runtime_error("cannot atomically replace telemetry spool: " + ec.message());
    }
}

} // namespace northstar
