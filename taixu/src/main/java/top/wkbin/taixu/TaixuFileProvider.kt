package top.wkbin.taixu

import androidx.core.content.FileProvider

/**
 * 太墟专用的 FileProvider。
 *
 * 宿主（塔菲逆核）自带一个 `androidx.core.content.FileProvider`，其 `<provider android:name>`
 * 与本模块完全同名，Manifest 合并器会把两条声明视作同一节点，进而要求
 * `android.support.FILE_PROVIDER_PATHS` 的 meta-data 资源二选一（`update_file_paths` 与
 * `taixu_file_paths` 冲突，直接合并失败）。
 *
 * 继承一层即可让两个 provider 各自保留独立的 paths 表：宿主继续用自己的 authority，
 * 太墟用 `${applicationId}.taixu.fileprovider`，互不干扰。
 */
class TaixuFileProvider : FileProvider()
