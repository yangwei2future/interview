# ============================================================
# Embodied AI Data Pipeline - Global Configuration
# ============================================================

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# Base paths
BASE_DIR = Path(__file__).parent.parent.parent  # project root
DATA_DIR = BASE_DIR / "data" / "embodied-ai"
RAW_DIR = DATA_DIR / "raw"       # raw collected episodes
PROCESSED_DIR = DATA_DIR / "processed"   # LeRobot format
CLEAN_DIR = DATA_DIR / "clean"        # cleaned data
ANNOTATED_DIR = DATA_DIR / "annotated"  # annotation results


@dataclass
class SimulationConfig:
    """ManiSkill simulation parameters."""
    task_name: str = "PickCube-v1"        # Pick-and-Place task
    num_episodes: int = 10                # number of episodes to collect
    max_steps_per_episode: int = 200      # max steps before timeout
    control_mode: str = "pd_joint_delta_pos"  # control mode
    control_freq: int = 20                # control frequency (Hz)
    sim_freq: int = 500                   # physics simulation frequency (Hz)

    # Camera config
    cameras: list = field(default_factory=lambda: ["cam_high", "cam_wrist"])
    resolution: tuple = (224, 224)        # camera resolution (H, W)

    # Domain randomization
    randomize_lighting: bool = True
    randomize_textures: bool = True


@dataclass
class StorageConfig:
    """Data storage / LeRobot format parameters."""
    format: str = "lerobot"               # output format
    chunk_size: int = 100                 # episodes per chunk
    video_codec: str = "libx264"          # video encoding codec
    video_fps: int = 20                    # exported video fps
    compression: str = "snappy"           # Parquet compression


@dataclass
class CleaningConfig:
    """Data cleaning rule thresholds."""
    # Joint limits (example for a 7-DOF arm)
    joint_angle_min: list = field(default_factory=lambda: [-3.14] * 7)
    joint_angle_max: list = field(default_factory=lambda: [3.14] * 7)

    # Timestamp
    max_dt_ratio: float = 3.0             # max delta_t / expected_interval

    # Image quality
    min_pixel_mean: float = 5.0           # reject frames darker than this
    max_pixel_mean: float = 250.0         # reject frames brighter than this
    blur_threshold: float = 100.0         # Laplacian variance for blur detection

    # Dedup
    ssim_similarity_threshold: float = 0.98  # frames with SSIM > this are duplicates
    still_frame_window: int = 10           # consecutive frames with no movement
    still_joint_threshold: float = 0.001   # max joint angle change to be "still"

    # Episode quality
    max_anomaly_frame_ratio: float = 0.05  # max ratio of anomalous frames in episode


@dataclass
class AnnotationConfig:
    """VLM auto-annotation parameters."""
    enabled: bool = False                 # disabled by default (API cost)
    provider: str = "openai"              # "openai" or "gemini"
    model: str = "gpt-4o"                 # VLM model name
    api_key: Optional[str] = None         # API key (read from env var if None)
    sample_frames: int = 8                # number of frames to sample per episode
    batch_size: int = 5                   # episodes per API call
    labels: tuple = (
        "APPROACH",    # arm approaching target object
        "GRASP",       # gripper closing to grasp object
        "LIFT",        # lifting object from surface
        "TRANSPORT",   # moving object to target location
        "PLACE",       # placing object at target
        "RETREAT",     # arm retreating, task complete
    )

    def __post_init__(self):
        if self.api_key is None:
            self.api_key = os.environ.get("OPENAI_API_KEY")


@dataclass
class PipelineConfig:
    """Top-level pipeline configuration."""
    simulation: SimulationConfig = field(default_factory=SimulationConfig)
    storage: StorageConfig = field(default_factory=StorageConfig)
    cleaning: CleaningConfig = field(default_factory=CleaningConfig)
    annotation: AnnotationConfig = field(default_factory=AnnotationConfig)

    # Pipeline control
    skip_collection: bool = False         # skip data collection (use existing)
    skip_cleaning: bool = False           # skip data cleaning
    skip_annotation: bool = False         # skip auto-annotation
    verbose: bool = True                  # print detailed logs


# Default config instance
default_config = PipelineConfig()
