#include "northstar_spool.hpp"
#include <cassert>
#include <filesystem>
#include <string>
#include <vector>

int main() {
    auto path = (std::filesystem::temp_directory_path() / "northstar-spool-test.ndjson").string();
    std::filesystem::remove(path);
    std::filesystem::remove(path + ".tmp");

    northstar::append_spool(path, "one");
    northstar::append_spool(path, "two");
    northstar::append_spool(path, "three");
    assert((northstar::read_spool(path) == std::vector<std::string>{"one", "two", "three"}));

    auto lines = northstar::read_spool(path);
    northstar::replace_spool_atomically(path, lines, 2);
    assert((northstar::read_spool(path) == std::vector<std::string>{"three"}));
    assert(!std::filesystem::exists(path + ".tmp"));

    lines = northstar::read_spool(path);
    northstar::replace_spool_atomically(path, lines, 1);
    assert(!std::filesystem::exists(path));
    return 0;
}
