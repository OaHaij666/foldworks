//! Pocket Homestead — Rust 合成规划器（稀疏数组优化版）
//!
//! 通过 JNI 导出，Java 侧通过 DirectByteBuffer 通信。
//! 核心优化：inventory 和 tools 使用稀疏数组（index = item_id），O(1) 查找。

use std::collections::{HashMap, HashSet};

// ── 常量 ──────────────────────────────────────────────────────────────────

/// 物品 ID 上限（u16 范围）
const MAX_ITEM_ID: usize = 65536;

// ── 数据结构 ──────────────────────────────────────────────────────────────

struct RecipeDatabase {
    output_index: HashMap<u16, Vec<usize>>,
    recipes: Vec<Recipe>,
}

struct Recipe {
    output_id: u16,
    output_count: u8,
    tool_id: u16,
    time_ticks: u32,
    inputs: Vec<Vec<u16>>,
}

struct Order {
    target_id: u16,
    requested: u32,
    ready: u32,
    active_target_output_count: u32,
}

#[derive(Clone, Copy)]
struct PlanResult {
    order_index: u8,
    tool_id: u16,
    output_id: u16,
    time_ticks: u32,
    is_target_output: bool,
}

// ── 持久化 Session ──────────────────────────────────────────────────────────

// 服务端单线程，所有 JNI 调用在 Server thread 串行执行，无需锁。
static mut SESSION: Option<PlannerSession> = None;

struct PlannerSession {
    db: RecipeDatabase,
    // 稀疏数组：index = item_id, value = count。O(1) 查找/修改
    inventory: Vec<u32>,
    tools: Vec<bool>,
    // 记录设置过的 id，clear 时只清这些位置（通常几十个），避免 memset 256KB
    used_inventory_ids: Vec<u16>,
    used_tool_ids: Vec<u16>,
    // first_missing_input 用的 reserved 稀疏数组，避免每次新建 Vec
    reserved: Vec<u32>,
    used_reserved_ids: Vec<u16>,
    // 其他状态
    orders: Vec<Order>,
    max_depth: u32,
    results: Vec<PlanResult>,
    output_buf: Vec<u8>,
    visited: HashSet<u16>,
}

impl PlannerSession {
    fn new(db: RecipeDatabase) -> Self {
        PlannerSession {
            db,
            inventory: vec![0u32; MAX_ITEM_ID],
            tools: vec![false; MAX_ITEM_ID],
            used_inventory_ids: Vec::with_capacity(256),
            used_tool_ids: Vec::with_capacity(64),
            reserved: vec![0u32; MAX_ITEM_ID],
            used_reserved_ids: Vec::with_capacity(32),
            orders: Vec::new(),
            max_depth: 10,
            results: Vec::new(),
            output_buf: Vec::with_capacity(4096),
            visited: HashSet::new(),
        }
    }

    #[inline]
    fn has_tool(&self, tool_id: u16) -> bool {
        self.tools[tool_id as usize]
    }

    #[inline]
    fn count_available(&self, id: u16) -> u32 {
        self.inventory[id as usize]
    }

    #[inline]
    fn consume(&mut self, id: u16, count: u32) {
        let slot = &mut self.inventory[id as usize];
        *slot = slot.saturating_sub(count);
    }

    #[inline]
    fn add(&mut self, id: u16, count: u32) {
        self.inventory[id as usize] += count;
    }

    /// 找第一个缺失的输入，返回 (input_index, candidate_ids)
    /// 使用预分配的 reserved 稀疏数组，避免每次新建 Vec
    fn first_missing_input(&mut self, recipe_idx: usize) -> Option<(usize, Vec<u16>)> {
        // clear 上次的 reserved
        for &id in &self.used_reserved_ids {
            self.reserved[id as usize] = 0;
        }
        self.used_reserved_ids.clear();

        let recipe = &self.db.recipes[recipe_idx];
        for (i, candidates) in recipe.inputs.iter().enumerate() {
            if candidates.is_empty() {
                continue;
            }
            let mut found = false;
            for &cand_id in candidates {
                let available = self.inventory[cand_id as usize] - self.reserved[cand_id as usize];
                if available > 0 {
                    if self.reserved[cand_id as usize] == 0 {
                        self.used_reserved_ids.push(cand_id);
                    }
                    self.reserved[cand_id as usize] += 1;
                    found = true;
                    break;
                }
            }
            if !found {
                return Some((i, candidates.clone()));
            }
        }
        None
    }

    #[inline]
    fn recipe_indices(&self, output_id: u16) -> &[usize] {
        self.db
            .output_index
            .get(&output_id)
            .map(|v| v.as_slice())
            .unwrap_or(&[])
    }

    fn try_start_for(
        &mut self,
        desired_id: u16,
        target_output: bool,
        order_index: u8,
        depth: u32,
    ) -> bool {
        if depth > self.max_depth {
            return false;
        }

        if !self.visited.insert(desired_id) {
            return false;
        }

        let result = self.try_start_for_inner(desired_id, target_output, order_index, depth);
        self.visited.remove(&desired_id);
        result
    }

    fn try_start_for_inner(
        &mut self,
        desired_id: u16,
        target_output: bool,
        order_index: u8,
        depth: u32,
    ) -> bool {
        // 先把索引拷贝出来，避免持有 db 的不可变借用
        let indices: Vec<usize> = self.recipe_indices(desired_id).to_vec();
        if indices.is_empty() {
            return false;
        }

        for recipe_idx in indices {
            // 读出 recipe 的 Copy 字段，不持有引用
            let (tool_id, output_id, output_count, time_ticks) = {
                let r = &self.db.recipes[recipe_idx];
                (r.tool_id, r.output_id, r.output_count, r.time_ticks)
            };
            if !self.has_tool(tool_id) {
                continue;
            }

            if let Some((_missing_idx, missing_candidates)) = self.first_missing_input(recipe_idx) {
                let mut found_material = false;
                for &cand_id in &missing_candidates {
                    let has_recipe = !self.recipe_indices(cand_id).is_empty();
                    let has_stock = self.count_available(cand_id) > 0;
                    if (has_recipe || has_stock)
                        && self.try_start_for(cand_id, false, order_index, depth + 1)
                    {
                        found_material = true;
                        break;
                    }
                }
                if !found_material {
                    continue;
                }
                if self.first_missing_input(recipe_idx).is_some() {
                    continue;
                }
            }

            self.consume_inputs(recipe_idx);

            self.results.push(PlanResult {
                order_index,
                tool_id,
                output_id,
                time_ticks,
                is_target_output: target_output,
            });

            self.add(output_id, output_count as u32);
            return true;
        }

        false
    }

    fn consume_inputs(&mut self, recipe_idx: usize) {
        // 把 inputs 拷贝出来，避免借用冲突（只在成功配方时调用，频率低）
        let inputs: Vec<Vec<u16>> = self.db.recipes[recipe_idx].inputs.clone();
        for candidates in &inputs {
            if candidates.is_empty() {
                continue;
            }
            for &cand_id in candidates {
                if self.count_available(cand_id) > 0 {
                    self.consume(cand_id, 1);
                    break;
                }
            }
        }
    }

    /// 更新状态（inventory/tools/orders/max_depth），复用稀疏数组
    fn update_state(&mut self, buf: &[u8]) -> bool {
        let mut pos = 0;
        if buf.is_empty() {
            return false;
        }

        let command = read_u8(buf, &mut pos);
        if command != 0x03 {
            return false;
        }

        self.max_depth = read_u32(buf, &mut pos);

        // clear 上次的 inventory（只清用过的位置）
        for &id in &self.used_inventory_ids {
            self.inventory[id as usize] = 0;
        }
        self.used_inventory_ids.clear();

        // inventory
        let inv_count = read_u16(buf, &mut pos) as usize;
        for _ in 0..inv_count {
            let id = read_u16(buf, &mut pos);
            let count = read_u32(buf, &mut pos);
            self.inventory[id as usize] = count;
            self.used_inventory_ids.push(id);
        }

        // clear 上次的 tools
        for &id in &self.used_tool_ids {
            self.tools[id as usize] = false;
        }
        self.used_tool_ids.clear();

        // tools
        let tool_count = read_u16(buf, &mut pos) as usize;
        for _ in 0..tool_count {
            let id = read_u16(buf, &mut pos);
            self.tools[id as usize] = true;
            self.used_tool_ids.push(id);
        }

        // orders
        self.orders.clear();
        let order_count = read_u8(buf, &mut pos) as usize;
        for _ in 0..order_count {
            let target_id = read_u16(buf, &mut pos);
            let requested = read_u32(buf, &mut pos);
            let ready = read_u32(buf, &mut pos);
            let active_target_output_count = read_u32(buf, &mut pos);
            self.orders.push(Order {
                target_id,
                requested,
                ready,
                active_target_output_count,
            });
        }

        true
    }

    /// 执行规划
    fn plan(&mut self) {
        self.results.clear();
        self.visited.clear();

        let order_count = self.orders.len();
        for order_idx in 0..order_count {
            let order = &self.orders[order_idx];
            if order.ready + order.active_target_output_count >= order.requested {
                continue;
            }
            self.visited.clear();
            self.try_start_for(order.target_id, true, order_idx as u8, 0);
        }
    }

    /// 编码结果到 output_buf
    fn encode_results(&mut self) -> &[u8] {
        self.output_buf.clear();
        self.output_buf.push(1); // version
        self.output_buf.push(self.results.len() as u8);
        for r in &self.results {
            self.output_buf.push(r.order_index);
            self.output_buf.extend_from_slice(&r.tool_id.to_le_bytes());
            self.output_buf.extend_from_slice(&r.output_id.to_le_bytes());
            self.output_buf.extend_from_slice(&r.time_ticks.to_le_bytes());
            self.output_buf.push(if r.is_target_output { 1 } else { 0 });
        }
        &self.output_buf
    }
}

// ── 二进制协议解码 ──────────────────────────────────────────────────────

#[inline]
fn read_u8(buf: &[u8], pos: &mut usize) -> u8 {
    let v = buf[*pos];
    *pos += 1;
    v
}

#[inline]
fn read_u16(buf: &[u8], pos: &mut usize) -> u16 {
    let v = u16::from_le_bytes([buf[*pos], buf[*pos + 1]]);
    *pos += 2;
    v
}

#[inline]
fn read_u32(buf: &[u8], pos: &mut usize) -> u32 {
    let v = u32::from_le_bytes([buf[*pos], buf[*pos + 1], buf[*pos + 2], buf[*pos + 3]]);
    *pos += 4;
    v
}

fn write_u8(buf: &mut Vec<u8>, v: u8) {
    buf.push(v);
}

fn write_u16(buf: &mut Vec<u8>, v: u16) {
    buf.extend_from_slice(&v.to_le_bytes());
}

fn write_u32(buf: &mut Vec<u8>, v: u32) {
    buf.extend_from_slice(&v.to_le_bytes());
}

// ── syncRecipes 解析 ─────────────────────────────────────────────────────

fn parse_sync_recipes(buf: &[u8]) -> Option<RecipeDatabase> {
    let mut pos = 0;
    if buf.is_empty() {
        return None;
    }

    let command = read_u8(buf, &mut pos);
    if command != 0x01 {
        return None;
    }

    // item dictionary（Rust 侧不需要名字，只跳过）
    let item_count = read_u16(buf, &mut pos) as usize;
    for _ in 0..item_count {
        let name_len = read_u16(buf, &mut pos) as usize;
        if pos + name_len > buf.len() {
            return None;
        }
        pos += name_len;
    }

    // recipes
    let recipe_count = read_u16(buf, &mut pos) as usize;
    let mut recipes = Vec::with_capacity(recipe_count);
    let mut output_index: HashMap<u16, Vec<usize>> = HashMap::new();
    for i in 0..recipe_count {
        let output_id = read_u16(buf, &mut pos);
        let output_count = read_u8(buf, &mut pos);
        let tool_id = read_u16(buf, &mut pos);
        let time_ticks = read_u16(buf, &mut pos) as u32;
        let input_count = read_u8(buf, &mut pos) as usize;
        let mut inputs = Vec::with_capacity(input_count);
        for _ in 0..input_count {
            let candidate_count = read_u8(buf, &mut pos) as usize;
            let mut candidates = Vec::with_capacity(candidate_count);
            for _ in 0..candidate_count {
                candidates.push(read_u16(buf, &mut pos));
            }
            inputs.push(candidates);
        }
        output_index.entry(output_id).or_default().push(i);
        recipes.push(Recipe {
            output_id,
            output_count,
            tool_id,
            time_ticks,
            inputs,
        });
    }

    Some(RecipeDatabase { output_index, recipes })
}

// ── JNI 导出 ──────────────────────────────────────────────────────────────

/// JNI: syncRecipes — 初始化或刷新配方数据库
#[no_mangle]
pub extern "system" fn Java_com_pockethomestead_suite_NativeCraftingPlanner_syncRecipes<'local>(
    env: jni::JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
    input: jni::objects::JByteBuffer<'local>,
    input_len: jni::sys::jint,
) -> jni::sys::jboolean {
    let input_ptr = match env.get_direct_buffer_address(&input) {
        Ok(ptr) => ptr,
        Err(_) => return 0,
    };

    if input_ptr.is_null() || input_len <= 0 {
        return 0;
    }

    let input_buf = unsafe { std::slice::from_raw_parts(input_ptr, input_len as usize) };

    let db = match parse_sync_recipes(input_buf) {
        Some(db) => db,
        None => return 0,
    };

    let session = PlannerSession::new(db);

    unsafe {
        SESSION = Some(session);
    }

    1
}

/// JNI: updateStateAndPlan — 更新状态 + 规划 + 返回结果
#[no_mangle]
pub extern "system" fn Java_com_pockethomestead_suite_NativeCraftingPlanner_updateStateAndPlan<'local>(
    env: jni::JNIEnv<'local>,
    _class: jni::objects::JClass<'local>,
    input: jni::objects::JByteBuffer<'local>,
    input_len: jni::sys::jint,
    output: jni::objects::JByteBuffer<'local>,
    output_capacity: jni::sys::jint,
) -> jni::sys::jint {
    let session = unsafe { SESSION.as_mut() };
    let session = match session {
        Some(s) => s,
        None => return 0,
    };

    let input_ptr = match env.get_direct_buffer_address(&input) {
        Ok(ptr) => ptr,
        Err(_) => return 0,
    };
    let output_ptr = match env.get_direct_buffer_address(&output) {
        Ok(ptr) => ptr,
        Err(_) => return 0,
    };

    if input_ptr.is_null() || output_ptr.is_null() || input_len <= 0 || output_capacity <= 0 {
        return 0;
    }

    let input_buf = unsafe { std::slice::from_raw_parts(input_ptr, input_len as usize) };

    if !session.update_state(input_buf) {
        return 0;
    }

    session.plan();
    let result_bytes = session.encode_results();

    let write_len = result_bytes.len().min(output_capacity as usize);
    if write_len > 0 {
        unsafe {
            std::ptr::copy_nonoverlapping(result_bytes.as_ptr(), output_ptr, write_len);
        }
    }

    write_len as jni::sys::jint
}

// ── 单元测试 ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn build_sync_input(items: &[&str], recipes: &[(&str, u8, &str, u16, Vec<Vec<&str>>)]) -> Vec<u8> {
        let mut buf = Vec::new();
        write_u8(&mut buf, 0x01);

        let name_to_id: HashMap<&str, u16> = items.iter().enumerate().map(|(i, &n)| (n, i as u16)).collect();

        write_u16(&mut buf, items.len() as u16);
        for name in items {
            let bytes = name.as_bytes();
            write_u16(&mut buf, bytes.len() as u16);
            buf.extend_from_slice(bytes);
        }

        write_u16(&mut buf, recipes.len() as u16);
        for &(output, output_count, tool, time_ticks, ref inputs) in recipes {
            write_u16(&mut buf, name_to_id[output]);
            write_u8(&mut buf, output_count);
            write_u16(&mut buf, name_to_id[tool]);
            write_u16(&mut buf, time_ticks);
            write_u8(&mut buf, inputs.len() as u8);
            for candidates in inputs {
                write_u8(&mut buf, candidates.len() as u8);
                for &c in candidates {
                    write_u16(&mut buf, name_to_id[c]);
                }
            }
        }

        buf
    }

    fn build_plan_input(
        max_depth: u32,
        inventory: &[(&str, u32)],
        tools: &[&str],
        orders: &[(&str, u32, u32, u32)],
        name_to_id: &HashMap<&str, u16>,
    ) -> Vec<u8> {
        let mut buf = Vec::new();
        write_u8(&mut buf, 0x03);
        write_u32(&mut buf, max_depth);

        write_u16(&mut buf, inventory.len() as u16);
        for &(name, count) in inventory {
            write_u16(&mut buf, name_to_id[name]);
            write_u32(&mut buf, count);
        }

        write_u16(&mut buf, tools.len() as u16);
        for &name in tools {
            write_u16(&mut buf, name_to_id[name]);
        }

        write_u8(&mut buf, orders.len() as u8);
        for &(target, requested, ready, active) in orders {
            write_u16(&mut buf, name_to_id[target]);
            write_u32(&mut buf, requested);
            write_u32(&mut buf, ready);
            write_u32(&mut buf, active);
        }

        buf
    }

    fn run_plan(sync_buf: &[u8], plan_buf: &[u8]) -> Vec<PlanResult> {
        let db = parse_sync_recipes(sync_buf).unwrap();
        let mut session = PlannerSession::new(db);
        assert!(session.update_state(plan_buf));
        session.plan();
        session.results.clone()
    }

    #[test]
    fn test_simple_craft() {
        let items = &["crafting_table", "planks", "stick"];
        let recipes = &[("stick", 4, "crafting_table", 20, vec![vec!["planks"]])];
        let sync_buf = build_sync_input(items, recipes);

        let name_to_id: HashMap<&str, u16> = items.iter().enumerate().map(|(i, &n)| (n, i as u16)).collect();
        let plan_buf = build_plan_input(
            10,
            &[("planks", 64)],
            &["crafting_table"],
            &[("stick", 4, 0, 0)],
            &name_to_id,
        );

        let results = run_plan(&sync_buf, &plan_buf);
        assert_eq!(results.len(), 1);
        assert!(results[0].is_target_output);
    }

    #[test]
    fn test_chain_craft() {
        let items = &["log", "planks", "stick", "crafting_table"];
        let recipes = &[
            ("planks", 4, "crafting_table", 20, vec![vec!["log"]]),
            ("stick", 4, "crafting_table", 20, vec![vec!["planks"]]),
        ];
        let sync_buf = build_sync_input(items, recipes);

        let name_to_id: HashMap<&str, u16> = items.iter().enumerate().map(|(i, &n)| (n, i as u16)).collect();
        let plan_buf = build_plan_input(
            10,
            &[("log", 64)],
            &["crafting_table"],
            &[("stick", 4, 0, 0)],
            &name_to_id,
        );

        let results = run_plan(&sync_buf, &plan_buf);
        assert_eq!(results.len(), 2);
    }

    #[test]
    fn test_no_recipe_found() {
        let items = &["something"];
        let recipes: &[(&str, u8, &str, u16, Vec<Vec<&str>>)] = &[];
        let sync_buf = build_sync_input(items, recipes);

        let name_to_id: HashMap<&str, u16> = items.iter().enumerate().map(|(i, &n)| (n, i as u16)).collect();
        let plan_buf = build_plan_input(
            10,
            &[],
            &[],
            &[("something", 1, 0, 0)],
            &name_to_id,
        );

        let results = run_plan(&sync_buf, &plan_buf);
        assert!(results.is_empty());
    }

    #[test]
    fn test_material_available_no_recursion() {
        let items = &["crafting_table", "planks", "stick"];
        let recipes = &[("stick", 4, "crafting_table", 20, vec![vec!["planks"]])];
        let sync_buf = build_sync_input(items, recipes);

        let name_to_id: HashMap<&str, u16> = items.iter().enumerate().map(|(i, &n)| (n, i as u16)).collect();
        let plan_buf = build_plan_input(
            10,
            &[("planks", 10)],
            &["crafting_table"],
            &[("stick", 4, 0, 0)],
            &name_to_id,
        );

        let results = run_plan(&sync_buf, &plan_buf);
        assert_eq!(results.len(), 1);
    }
}
