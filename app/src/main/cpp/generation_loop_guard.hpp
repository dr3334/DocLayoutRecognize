#pragma once
#include <algorithm>
#include <string>
#include <string_view>

namespace dococr {
// 只查看有界尾窗，不改采样参数。触发后由调用方停止下一次 decode；证据原样保留。
class GenerationLoopGuard {
public:
    explicit GenerationLoopGuard(bool table) : table_(table) {}
    void append(const char* data, size_t size) {
        if (detected()) return;
        for (size_t i = 0; i < size; ++i) {
            const char ch = data[i];
            if (ch == '\n') {
                if (!table_ && !line_.empty()) {
                    if (empty_number(line_)) ++empty_lines_;
                    else if (line_.find_first_not_of(" \t\r") != std::string::npos) empty_lines_ = 0;
                }
                line_.clear();
            } else if (line_.size() < 512) line_ += ch;
        }
        if (empty_lines_ >= 12) { reason_ = "empty_numbering"; return; }
        tail_.append(data, size);
        if (tail_.size() > 4096) tail_.erase(0, tail_.size() - 4096);
        unchecked_ += size;
        if (unchecked_ < 16 || tail_.size() < 96 || !complete_utf8_tail()) return;
        unchecked_ = 0;
        const size_t n = tail_.size();
        for (size_t width = 1; width <= 256 && width * 8 <= n; ++width) {
            const size_t start = n - width;
            if ((static_cast<unsigned char>(tail_[start]) & 0xc0) == 0x80) continue;
            const std::string_view unit(tail_.data() + start, width);
            // 相同单元格/行、Markdown 分隔线、公式结构允许重复；只拦截单元格内的循环。
            if (unit.find_first_not_of(" \t\r\n-=_") == std::string_view::npos) continue;
            if (table_ && unit.find_first_of("<>|\\&") != std::string_view::npos) continue;
            size_t count = 1;
            while ((count + 1) * width <= n &&
                   tail_.compare(n - (count + 1) * width, width, tail_, start, width) == 0) ++count;
            if (count >= 8 && count * width >= 96) { reason_ = "repeated_fragment"; return; }
        }
    }
    bool detected() const { return !reason_.empty(); }
    const std::string& reason() const { return reason_; }
private:
    static bool empty_number(std::string_view line) {
        size_t i = line.find_first_not_of(" \t\r#");
        if (i == std::string_view::npos) return false;
        if (line[i] == '(') ++i;
        const size_t first = i;
        while (i < line.size() && line[i] >= '0' && line[i] <= '9') ++i;
        if (i == first) return false;
        return line.find_first_not_of(".)：: \t\r", i) == std::string_view::npos;
    }
    bool complete_utf8_tail() const {
        size_t start = tail_.size() - 1;
        while (start > 0 && (static_cast<unsigned char>(tail_[start]) & 0xc0) == 0x80) --start;
        const unsigned char ch = tail_[start];
        const size_t width = ch < 0x80 ? 1 : ch >= 0xc2 && ch <= 0xdf ? 2 :
                             ch >= 0xe0 && ch <= 0xef ? 3 : ch >= 0xf0 && ch <= 0xf4 ? 4 : 0;
        return width && start + width == tail_.size();
    }
    bool table_;
    size_t unchecked_ = 0, empty_lines_ = 0;
    std::string tail_, line_, reason_;
};
}
