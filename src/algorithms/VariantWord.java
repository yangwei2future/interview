package algorithms;


import java.util.*;

/**
 * 字母异位词分组
 * 示例 1:
 *
 * 输入: strs = ["eat", "tea", "tan", "ate", "nat", "bat"]
 *
 * 输出: [["bat"],["nat","tan"],["ate","eat","tea"]]
 *
 * 解释：
 *
 * 在 strs 中没有字符串可以通过重新排列来形成 "bat"。
 * 字符串 "nat" 和 "tan" 是字母异位词，因为它们可以重新排列以形成彼此。
 * 字符串 "ate" ，"eat" 和 "tea" 是字母异位词，因为它们可以重新排列以形成彼此。
 */
public class VariantWord {

    public static void main(String[] args) {
        List<String> strs = Arrays.asList("eat", "tea", "tan", "ate", "nat", "bat");
        List<List<String>> variantWord = getVariantWord(strs);
        System.out.println(variantWord);
    }

    static List<List<String>> getVariantWord(List<String> input){
        List<List<String>> res = new ArrayList<>();
        HashMap<String, List<String>> resultMap = new HashMap<>();
        for (String word : input) {
            String sorted = sortWord(word);
            List<String> single = resultMap.get(sorted);
            if (Objects.nonNull(single) && !single.contains(sorted)) {
                single.add(word);
                resultMap.put(sorted, single);
            }else {
                ArrayList<String> cur = new ArrayList<>();
                cur.add(word);
                resultMap.put(sorted,cur);
            }
        }
        for (Map.Entry<String, List<String>> entry : resultMap.entrySet()) {
            res.add(entry.getValue());
        }
        return res;
    }

    static String sortWord(String word){
        char[] charSort = word.toCharArray();
        Arrays.sort(charSort);
        return new String(charSort);
    }
}
