package algorithms;

import java.util.*;

public class threeTuple {

    public static void main(String[] args) {
        ArrayList<Integer> list = new ArrayList<>(Arrays.asList(1,2,3,4,5,6,7));
        Integer target = 6;

        List<List<Integer>> three = three(list, target);
        System.out.println(three);

    }

    static List<List<Integer>> three(List<Integer> list, Integer target){
        ArrayList<List<Integer>> res = new ArrayList<>();
        HashMap<Integer, Integer> map = new HashMap<>();
        for (Integer i : list) {
            map.put(i, 0);
        }
        for (Integer i : list) {
            Integer adds = map.get(target - i);
            if (Objects.equals(adds, 0) && i != target-i) {
                ArrayList<Integer> tas = new ArrayList<>(Arrays.asList(i, target - i, target));
                map.put(i, 1);
                map.put(target - i, 1);
                res.add(tas);
            }
        }
        return res;
    }
}
