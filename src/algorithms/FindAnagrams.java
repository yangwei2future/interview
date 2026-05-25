package algorithms;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class FindAnagrams {

    public static List<Integer> findAnagrams(String s, String p) {
        ArrayList<Integer> res = new ArrayList<>();
        if (p.length() > s.length()) {
            return res;
        }
        char[] chars = s.toCharArray();
        int length = p.length();
        HashMap<Character, Integer> map = new HashMap<>();
        for (int i = 0; i <= chars.length - length; i++) {
            for (int j = i; j < i + length; j++) {
                Integer i1 = map.get(chars[j]);
                if (Objects.isNull(i1) && p.contains(String.valueOf(chars[j]))) {
                    map.put(chars[j], j);
                }
            }
            if (map.entrySet().size() == length) {
                res.add(i);
            }
            map.clear();
        }

        return res;
    }

    /**
     * 定长滑动窗口 + 频率数组 (O(n))
     *
     * 核心思路：窗口固定为 p 的长度，每次只「右进一个，左出一个」，
     *           用 int[26] 维护窗口内 26 个字母各出现几次，
     *           窗口满了就和 need 数组比对 26 个字母频率是否全等。
     *
     * 为什么用 int[26] 而非 HashMap？
     *   HashMap: hashCode() → 找桶 → 链表/红黑树 → get/put  (常数大)
     *   int[26]:  c - 'a' → 数组下标 → 直接读写              (一条 CPU 指令)
     *   matches  比较时，int[26] 是 26 次相邻内存的 int 比较，缓存友好
     *
     * 时间复杂度: O(n) — right/left 各自遍历一次 s，matches 每次固定 26 次比较
     * 空间复杂度: O(1) — 两个 int[26] 固定大小
     *
     * s = "cbaebabacd", p = "abc", pLen = 3
     *
     *   need = [a:1, b:1, c:1, 其余:0]
     *
     *   c b a e b a b a c d
     *   [     ]              ← right=0,1,2 依次进 c,b,a, 窗口满 → 匹配✅ left=0
     *     [     ]            ← 出c进e → 不匹配 ❌
     *       [     ]          ← 出b进b → 不匹配 ❌
     *         [     ]        ← 出a进a → 不匹配 ❌
     *           [     ]      ← 出e进b → 不匹配 ❌
     *             [     ]    ← 出b进a → 不匹配 ❌
     *               [     ]  ← 出a进c → 匹配✅ left=6
     *                 [     ]← 出b进d → 不匹配 ❌  窗口越界, 循环结束
     *
     *   结果: [0, 6]
     */
    public static List<Integer> findAnagrams2(String s, String p) {
        List<Integer> res = new ArrayList<>();
        if (p.length() > s.length()) {
            return res;
        }

        int[] need = new int[26];   // p 的字符频率表（目标）
        int[] window = new int[26]; // 滑动窗口内字符频率表

        for (char c : p.toCharArray()) {
            need[c - 'a']++;
        }

        int left = 0, right = 0;
        int pLen = p.length();

        while (right < s.length()) {
            // 1. right 指针向右扩张，窗口右边界进一个字符
            char c = s.charAt(right);
            window[c - 'a']++;
            right++;

            // 2. 窗口大小等于 p.length() 时，判断并收缩
            if (right - left == pLen) {
                // 2a. 比较 need 和 window 的 26 个字母频率是否完全一致
                if (matches(need, window)) {
                    res.add(left);
                }
                // 2b. left 指针向右收缩，窗口左边界出一个字符
                window[s.charAt(left) - 'a']--;
                left++;
            }
        }

        return res;
    }

    private static boolean matches(int[] need, int[] window) {
        for (int i = 0; i < 26; i++) {
            if (need[i] != window[i]) {
                return false;
            }
        }
        return true;
    }

    public static List<Integer> findAnagrams3(String s, String p) {
        List<Integer> res = new ArrayList<>();
        if (p.length() > s.length()) {
            return res;
        }

        // 1. 预先统计 p 的字符频率
        HashMap<Character, Integer> target = new HashMap<>();
        for (char c : p.toCharArray()) {
            target.put(c, target.getOrDefault(c, 0) + 1);
        }

        int n = s.length();
        int len = p.length();
        HashMap<Character, Integer> window = new HashMap<>();

        for (int i = 0; i <= n - len; i++) {
            // 2. 统计当前窗口内每个字符出现次数（不是位置）
            for (int j = i; j < i + len; j++) {
                char c = s.charAt(j);
                window.put(c, window.getOrDefault(c, 0) + 1);
            }
            // 3. 比较两个频率 map 是否相等
            if (window.equals(target)) {
                res.add(i);
            }
            window.clear();
        }

        return res;
    }

    public static void main(String[] args) {
        System.out.println("findAnagrams:  " + findAnagrams("cbaebabacd", "abc"));
        System.out.println("findAnagrams2: " + findAnagrams2("cbaebabacd", "abc"));
        System.out.println("findAnagrams3: " + findAnagrams3("cbaebabacd", "abc"));
        System.out.println();
        System.out.println("findAnagrams:  " + findAnagrams("abaa", "aab"));
        System.out.println("findAnagrams2: " + findAnagrams2("abaa", "aab"));
        System.out.println("findAnagrams3: " + findAnagrams3("abaa", "aab"));
    }
}
