# ============================================================
# Embodied AI Data Collection
# ManiSkill simulation for robot manipulation data acquisition.
# Supports both real ManiSkill and synthetic mock data for testing.
# ============================================================

import argparse
import json
import logging
import os
import shutil
import time
from pathlib import Path
from typing import Optional

import numpy as np
from PIL import Image

from config import SimulationConfig, RAW_DIR, default_config

logger = logging.getLogger(__name__)


# ---- Episode Data Structure ----
class EpisodeCollector:
    """Collects and stores observation/action data for a single episode."""

    def __init__(self, episode_id: int, save_dir: Path):
        self.episode_id = episode_id
        self.save_dir = save_dir / f"episode_{episode_id:03d}"
        self.steps: list[dict] = []
        self._step_count = 0

        if self.save_dir.exists():
            shutil.rmtree(self.save_dir)
        self.save_dir.mkdir(parents=True, exist_ok=True)

    def add_step(self, obs: dict, action: np.ndarray, reward: float, done: bool):
        step_dir = self.save_dir / f"step_{self._step_count:04d}"
        step_dir.mkdir(exist_ok=True)

        # Save camera images
        for cam_name in ["cam_high", "cam_wrist"]:
            if cam_name in obs:
                img = obs[cam_name]
                if isinstance(img, np.ndarray):
                    Image.fromarray(img).save(step_dir / f"{cam_name}.jpg")

        # Save depth images (as numpy for precision)
        for cam_name in ["cam_high", "cam_wrist"]:
            depth_key = f"depth_{cam_name.replace('cam_', '')}"
            if depth_key in obs:
                np.save(step_dir / f"depth_{cam_name.replace('cam_', '')}.npy",
                        obs[depth_key])

        # Save state (joint angles, EE pose, gripper state)
        state = {
            "joint_positions": obs.get("joint_positions", []).tolist()
            if isinstance(obs.get("joint_positions"), np.ndarray)
            else obs.get("joint_positions", []),
            "ee_pose": obs.get("ee_pose", []).tolist()
            if isinstance(obs.get("ee_pose"), np.ndarray)
            else obs.get("ee_pose", []),
            "gripper_state": float(obs.get("gripper_state", 0)),
            "action": action.tolist() if isinstance(action, np.ndarray) else list(action),
            "reward": float(reward),
            "done": bool(done),
            "timestamp": time.time(),
        }
        with open(step_dir / "state.json", "w") as f:
            json.dump(state, f, indent=2)

        self.steps.append(state)
        self._step_count += 1

    def finalize(self, success: bool, total_reward: float):
        """Write episode metadata after collection completes."""
        meta = {
            "episode_id": self.episode_id,
            "total_steps": self._step_count,
            "success": success,
            "total_reward": total_reward,
            "task": default_config.simulation.task_name,
        }
        with open(self.save_dir / "meta.json", "w") as f:
            json.dump(meta, f, indent=2)
        return meta

    @property
    def step_count(self) -> int:
        return self._step_count


# ---- Real ManiSkill Collector ----
class ManiSkillCollector:
    """Data collector using ManiSkill simulation."""

    def __init__(self, config: SimulationConfig):
        self.config = config

    def run(self, num_episodes: Optional[int] = None) -> list[dict]:
        """Run simulation and collect episode data."""
        if num_episodes is None:
            num_episodes = self.config.num_episodes

        try:
            import mani_skill.envs
            import gymnasium as gym
        except ImportError:
            logger.error(
                "ManiSkill not installed. Install with: pip install mani_skill\n"
                "Alternatively, use synthetic data: "
                "python data_collection.py --synthetic --episodes 3"
            )
            raise

        logger.info(f"Starting ManiSkill collection: {num_episodes} episodes, "
                     f"task={self.config.task_name}")

        env = gym.make(
            self.config.task_name,
            obs_mode="rgbd",           # RGB + Depth
            control_mode=self.config.control_mode,
            render_mode="rgb_array",
            sim_freq=self.config.sim_freq,
            control_freq=self.config.control_freq,
            camera_width=self.config.resolution[1],
            camera_height=self.config.resolution[0],
        )

        RAW_DIR.mkdir(parents=True, exist_ok=True)
        episode_metas = []

        for ep_idx in range(num_episodes):
            obs, _ = env.reset()
            collector = EpisodeCollector(ep_idx, RAW_DIR)
            total_reward = 0.0
            success = False

            for step in range(self.config.max_steps_per_episode):
                action = env.action_space.sample()  # random policy for demo
                obs, reward, terminated, truncated, info = env.step(action)
                done = terminated or truncated

                # Extract observations
                obs_dict = {
                    "cam_high": obs["image"]["cam_high"][:, :, :3],  # RGB channels
                    "cam_wrist": obs.get("image", {}).get(
                        "cam_wrist", obs["image"]["cam_high"]
                    )[:, :, :3],
                    "depth_high": obs["image"]["cam_high"][:, :, 3]
                    if obs["image"]["cam_high"].shape[-1] > 3 else np.zeros(
                        self.config.resolution
                    ),
                    "depth_wrist": obs.get("image", {}).get(
                        "cam_wrist", obs["image"]["cam_high"]
                    )[:, :, 3]
                    if obs.get("image", {}).get("cam_wrist",
                        obs["image"]["cam_high"]).shape[-1] > 3
                    else np.zeros(self.config.resolution),
                    "joint_positions": obs["agent"]["qpos"],
                    "ee_pose": obs["agent"].get("ee_pose", np.zeros(7)),
                    "gripper_state": obs["agent"].get("gripper_pos", 0),
                }

                collector.add_step(obs_dict, action, float(reward), done)
                total_reward += float(reward)

                if info.get("success", False):
                    success = True

                if done:
                    break

            meta = collector.finalize(success, total_reward)
            episode_metas.append(meta)
            logger.info(
                f"Episode {ep_idx}: {collector.step_count} steps, "
                f"success={success}, reward={total_reward:.2f}"
            )

        env.close()
        logger.info(f"Collection complete: {len(episode_metas)} episodes saved to {RAW_DIR}")
        return episode_metas


# ---- Synthetic Mock Collector (fallback for testing) ----
class SyntheticCollector:
    """Generates synthetic robot data for pipeline testing without ManiSkill.

    Produces realistic-looking dummy data with the same structure as real
    ManiSkill output. Useful for testing storage, cleaning, and annotation
    pipelines before setting up the full simulation environment.
    """

    def __init__(self, config: SimulationConfig):
        self.config = config

    def run(self, num_episodes: Optional[int] = None) -> list[dict]:
        if num_episodes is None:
            num_episodes = self.config.num_episodes

        logger.info(f"Generating synthetic data: {num_episodes} episodes")
        RAW_DIR.mkdir(parents=True, exist_ok=True)
        episode_metas = []
        rng = np.random.default_rng(42)

        # Simulated 7-DOF arm joint limits
        JOINT_LIMITS = [
            (-2.89, 2.89), (-1.76, 1.76), (-2.89, 2.89),
            (-3.07, -0.01), (-2.89, 2.89), (-0.01, 3.75), (-2.89, 2.89),
        ]

        for ep_idx in range(num_episodes):
            # Vary episode properties
            is_success = rng.random() > 0.2  # 80% success rate
            n_steps = rng.integers(50, self.config.max_steps_per_episode)

            collector = EpisodeCollector(ep_idx, RAW_DIR)
            joint_pos = np.array([rng.uniform(lo, hi) for lo, hi in JOINT_LIMITS])

            # Simulate a pick-and-place trajectory
            for step in range(n_steps):
                phase = step / n_steps

                # Joint angles: smooth trajectory + noise
                if is_success:
                    target = np.array([
                        0.5 * np.sin(phase * np.pi * 2),
                        -0.3 + 0.6 * phase,
                        0.4 * np.cos(phase * np.pi),
                        -1.5 + 0.5 * phase,
                        0.3 * np.sin(phase * np.pi * 3),
                        1.0 + 0.5 * np.sin(phase * np.pi),
                        0.2 * np.cos(phase * np.pi * 2),
                    ])
                else:
                    # Failed episode: erratic trajectory after midpoint
                    if phase < 0.5:
                        target = np.array([
                            0.5 * np.sin(phase * np.pi * 2),
                            -0.3 + 0.6 * phase,
                            0.4 * np.cos(phase * np.pi),
                            -1.5 + 0.5 * phase,
                            0.3 * np.sin(phase * np.pi * 3),
                            1.0 + 0.5 * np.sin(phase * np.pi),
                            0.2 * np.cos(phase * np.pi * 2),
                        ])
                    else:
                        target = joint_pos + rng.normal(0, 0.5, 7)  # random drift

                joint_pos = 0.9 * joint_pos + 0.1 * target + rng.normal(0, 0.002, 7)

                # Synthetic images: colored noise patterns that change with joint config
                h, w = self.config.resolution
                cam_high = self._make_synthetic_image(h, w, rng, phase, "high")
                cam_wrist = self._make_synthetic_image(h, w, rng, phase, "wrist")
                depth_high = (100 + 200 * phase + rng.normal(0, 2, (h, w))).astype(np.float32)
                depth_wrist = (80 + 150 * phase + rng.normal(0, 3, (h, w))).astype(np.float32)

                # EE pose: smooth arc trajectory
                ee_pose = np.array([
                    0.3 * np.cos(phase * np.pi),
                    0.2 + 0.3 * phase,
                    0.1 + 0.2 * np.sin(phase * np.pi),
                    1.0, 0.0, 0.0, 0.0,  # identity quaternion
                ])

                obs_dict = {
                    "cam_high": cam_high,
                    "cam_wrist": cam_wrist,
                    "depth_high": depth_high,
                    "depth_wrist": depth_wrist,
                    "joint_positions": joint_pos.copy(),
                    "ee_pose": ee_pose,
                    "gripper_state": float(phase > 0.3),  # grasp after approach
                }

                action = target - joint_pos + rng.normal(0, 0.01, 7)
                reward = 0.1 if is_success else -0.05
                done = (step == n_steps - 1)

                collector.add_step(obs_dict, action, reward, done)

            meta = collector.finalize(is_success, 0.0)
            episode_metas.append(meta)
            logger.info(
                f"Episode {ep_idx}: {collector.step_count} steps, "
                f"success={is_success}, synthetic=True"
            )

        logger.info(f"Synthetic data generated: {len(episode_metas)} episodes in {RAW_DIR}")
        return episode_metas

    @staticmethod
    def _make_synthetic_image(h: int, w: int, rng: np.random.Generator,
                               phase: float, cam: str) -> np.ndarray:
        """Generate a synthetic scene image that changes with task phase."""
        img = np.zeros((h, w, 3), dtype=np.uint8)

        # Background gradient
        bg_color = np.array([40, 40, 80]) if cam == "high" else np.array([30, 50, 30])
        for i in range(h):
            img[i, :] = bg_color + int(20 * i / h)

        # Table surface (bottom third)
        table_y = int(h * 0.65)
        img[table_y:, :] = [120, 90, 60]  # brown table

        # Robot arm segments (simple geometric proxy)
        arm_color = [180, 180, 180]  # gray

        # Base position
        base_x, base_y = w // 3, h // 2
        # End-effector position moves with phase
        ee_x = int(w * 0.3 + w * 0.3 * np.cos(phase * np.pi))
        ee_y = int(h * 0.6 - h * 0.3 * phase)

        # Draw arm line
        for t in np.linspace(0, 1, 20):
            x = int(base_x + (ee_x - base_x) * t)
            y = int(base_y + (ee_y - base_y) * t)
            if 0 <= x < w and 0 <= y < h:
                img[max(0, y-3):min(h, y+3), max(0, x-3):min(w, x+3)] = arm_color

        # Target object (cube)
        cx, cy = int(w * 0.55), int(h * 0.5)
        half = 12
        if phase > 0.4:  # cube moves with gripper after grasp
            cx, cy = ee_x, ee_y
        cube_color = [220, 50, 50]  # red cube
        img[max(0, cy-half):min(h, cy+half),
            max(0, cx-half):min(w, cx+half)] = cube_color

        # Goal position marker
        gx, gy = int(w * 0.7), int(h * 0.45)
        img[max(0, gy-15):min(h, gy+15),
            max(0, gx-15):min(w, gx+15)] = [50, 200, 50]  # green goal

        # Add noise
        noise = rng.integers(-10, 10, (h, w, 3))
        img = np.clip(img.astype(np.int32) + noise, 0, 255).astype(np.uint8)

        return img


# ---- CLI ----
def main():
    parser = argparse.ArgumentParser(description="Collect embodied AI simulation data")
    parser.add_argument("--episodes", type=int, default=None,
                        help="Number of episodes (default: from config)")
    parser.add_argument("--task", type=str, default=None,
                        help="ManiSkill task name")
    parser.add_argument("--synthetic", action="store_true",
                        help="Use synthetic mock data (no simulation needed)")
    parser.add_argument("--output", type=str, default=None,
                        help="Output directory for raw data")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s [%(levelname)s] %(message)s")

    config = default_config.simulation
    if args.task:
        config.task_name = args.task
    if args.output:
        global RAW_DIR
        RAW_DIR = Path(args.output)

    try:
        if args.synthetic:
            collector = SyntheticCollector(config)
        else:
            collector = ManiSkillCollector(config)

        metas = collector.run(num_episodes=args.episodes)

        # Summary
        successes = sum(1 for m in metas if m["success"])
        print(f"\n{'='*50}")
        print(f"Collection Summary")
        print(f"{'='*50}")
        print(f"Total episodes: {len(metas)}")
        print(f"Successful:     {successes}")
        print(f"Failed:         {len(metas) - successes}")
        print(f"Output:         {RAW_DIR}")

    except ImportError as e:
        logger.error(str(e))
        print("\nTip: Use --synthetic flag to generate mock data for testing:")
        print("  python data_collection.py --synthetic --episodes 3")


if __name__ == "__main__":
    main()
