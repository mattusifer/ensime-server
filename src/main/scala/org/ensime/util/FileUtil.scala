package org.ensime.util

import java.io._
import java.nio.charset.Charset
import java.security.MessageDigest
import scala.collection.Seq
import scala.collection.mutable
import scala.tools.nsc.io.AbstractFile
import scala.reflect.io.ZipArchive

// This routine copied from http://rosettacode.org/wiki/Walk_a_directory/Recursively#Scala

trait FileEdit {
  def file: File
  def text: String
  def from: Int
  def to: Int
}
case class TextEdit(file: File, from: Int, to: Int, text: String) extends FileEdit

case class NewFile(file: File, text: String) extends FileEdit {
  def from: Int = 0
  def to: Int = text.length - 1
}
case class DeleteFile(file: File, text: String) extends FileEdit {
  def from: Int = 0
  def to: Int = text.length - 1
}

object FileEdit {

  import scala.tools.refactoring.common.{ TextChange, NewFileChange, Change }

  def fromChange(ch: Change): FileEdit = {
    ch match {
      case ch: TextChange => TextEdit(ch.file.file, ch.from, ch.to, ch.text)
      case ch: NewFileChange => NewFile(ch.file, ch.text)
    }
  }

  def applyEdits(ch: List[TextEdit], source: String): String = {
    (source /: ch.sortBy(-_.to)) { (src, change) =>
      src.substring(0, change.from) + change.text + src.substring(change.to)
    }
  }

}

/** A wrapper around file, allowing iteration either on direct children or on directory tree */
class RichFile(file: File) {

  def children: Iterable[File] = new Iterable[File] {
    override def iterator: Iterator[File] = if (file.isDirectory) file.listFiles.iterator else Iterator.empty
  }

  def andTree: Iterable[File] = Seq(file) ++ children.flatMap(child => new RichFile(child).andTree)

}

/** implicitely enrich java.io.File with methods of RichFile */
object RichFile {
  implicit def toRichFile(file: File): RichFile = new RichFile(file)
}

class CanonFile private (path: String) extends File(path)

object CanonFile {
  def apply(file: File): CanonFile = {
    try {
      new CanonFile(file.getCanonicalPath)
    } catch {
      case e: Exception => new CanonFile(file.getAbsolutePath)
    }
  }

  def apply(str: String): CanonFile = apply(new File(str))
}

object FileUtils {

  def error[T](s: String): T = {
    throw new IOException(s)
  }

  implicit def toRichFile(file: File): RichFile = new RichFile(file)

  implicit def toCanonFile(file: File): CanonFile = CanonFile(file)

  def expandRecursively(rootDir: File, fileList: Iterable[File], isValid: (File => Boolean)): Set[CanonFile] = {
    (for (
      f <- fileList;
      files = if (f.isAbsolute) f.andTree else new File(rootDir, f.getPath).andTree;
      file <- files if isValid(file)
    ) yield { toCanonFile(file) }).toSet
  }

  // NOTE: Taken from ZipArchive internals to replace deepIterator that will be removed 2.11
  private def walkIterator(its: Iterator[AbstractFile]): Iterator[AbstractFile] = {
    its flatMap { f =>
      if (f.isDirectory) walkIterator(f.iterator)
      else Iterator(f)
    }
  }

  def expandSourceJars(fileList: Iterable[CanonFile]): Iterable[AbstractFile] = {
    fileList.flatMap { f =>
      if (isValidArchive(f)) {
        walkIterator(ZipArchive.fromFile(f).iterator).filter(f => isValidSourceName(f.name))
      } else {
        Seq(AbstractFile.getFile(f))
      }
    }
  }

  def canonicalizeDirs(names: Iterable[String], baseDir: File): Iterable[CanonFile] = {
    names.map { s => canonicalizeDir(s, baseDir) }.flatten
  }

  def canonicalizeFiles(names: Iterable[String], baseDir: File): Iterable[CanonFile] = {
    names.map { s => canonicalizeFile(s, baseDir) }.flatten
  }

  def canonicalizeFile(s: String, baseDir: File): Option[CanonFile] = {
    val f = new File(s)
    if (f.isAbsolute) Some(toCanonFile(f))
    else Some(toCanonFile(new File(baseDir, s)))
  }.filter(f => f.exists)

  def canonicalizeDir(s: String, baseDir: File): Option[CanonFile] = {
    canonicalizeFile(s, baseDir).filter(_.isDirectory)
  }

  def isValidJar(f: File): Boolean = f.exists && f.getName.endsWith(".jar")
  def isValidArchive(f: File): Boolean = f.exists && (f.getName.endsWith(".jar") || f.getName.endsWith(".zip"))
  def isValidClassDir(f: File): Boolean = f.exists && f.isDirectory
  def isValidSourceName(filename: String) = {
    filename.endsWith(".scala") || filename.endsWith(".java")
  }

  def isValidSourceFile(f: File): Boolean = {
    f.exists && !f.isHidden && isValidSourceName(f.getName)
  }

  def isValidSourceOrArchive(f: File): Boolean = {
    isValidSourceFile(f) || isValidArchive(f)
  }

  def isScalaSourceFile(f: File): Boolean = {
    f.exists && f.getName.endsWith(".scala")
  }

  def createDirectory(dir: File): Unit = {
    def failBase = "Could not create directory " + dir
    // Need a retry because mkdirs() has a race condition
    var tryCount = 0
    while (!dir.exists && !dir.mkdirs() && tryCount < 100) { tryCount += 1 }
    if (dir.isDirectory)
      ()
    else if (dir.exists) {
      error(failBase + ": file exists and is not a directory.")
    } else
      error(failBase)
  }

  def listFiles(dir: File): Array[File] = Option(dir.listFiles()).getOrElse(new Array[File](0))

  def delete(files: Iterable[File]): Unit = files.foreach(delete)

  def delete(file: File): Unit = {
    if (file.isDirectory) {
      delete(listFiles(file))
      file.delete
    } else if (file.exists)
      file.delete
  }

  def hexifyBytes(b: Array[Byte]): String = {
    var result = ""
    var i: Int = 0
    while (i < b.length) {
      result += Integer.toString((b(i) & 0xff) + 0x100, 16).substring(1)
      i += 1
    }
    result
  }

  def md5(f: File): String = {
    val buffer = new Array[Byte](1024)
    val hash = MessageDigest.getInstance("MD5")
    for (f <- f.andTree) {
      try {
        hash.update(f.getAbsolutePath.getBytes)
        if (!f.isDirectory) {
          val fis = new FileInputStream(f)
          var numRead = 0
          do {
            numRead = fis.read(buffer)
            if (numRead > 0) {
              hash.update(buffer, 0, numRead)
            }
          } while (numRead != -1)
          fis.close()
        }
      } catch {
        case e: IOException =>
          e.printStackTrace()
      }
    }
    hexifyBytes(hash.digest())
  }

  def readFile(file: File): Either[IOException, String] = {
    val cs = Charset.defaultCharset()
    try {
      val stream = new FileInputStream(file)
      try {
        val reader = new BufferedReader(new InputStreamReader(stream, cs))
        val builder = new StringBuilder()
        val buffer = new Array[Char](8192)
        var read = reader.read(buffer, 0, buffer.length)
        while (read > 0) {
          builder.appendAll(buffer, 0, read)
          read = reader.read(buffer, 0, buffer.length)
        }
        Right(builder.toString())
      } catch {
        case e: IOException => Left(e)
      } finally {
        stream.close()
      }
    } catch {
      case e: FileNotFoundException => Left(e)
    }
  }

  def replaceFileContents(file: File, newContents: String): Either[Exception, Unit] = {
    try {
      val writer = new FileWriter(file, false)
      try {
        writer.write(newContents)
        Right(())
      } catch {
        case e: IOException => Left(e)
      } finally {
        writer.close()
      }
    } catch {
      case e: Exception => Left(e)
    }
  }

  // Note: we assume changes do not overlap
  def inverseEdits(edits: Iterable[FileEdit]): List[FileEdit] = {
    val result = new mutable.ListBuffer[FileEdit]
    val editsByFile = edits.groupBy(_.file)
    editsByFile.foreach {
      case (file, edits) =>
        readFile(file) match {
          case Right(contents) =>
            var dy = 0
            for (ch <- edits) {
              ch match {
                case ch: TextEdit =>
                  val original = contents.substring(ch.from, ch.to)
                  val from = ch.from + dy
                  val to = from + ch.text.length
                  result += TextEdit(ch.file, from, to, original)
                  dy += ch.text.length - original.length
                case ch: NewFile =>
                  result += DeleteFile(ch.file, contents)
                case ch: DeleteFile =>
                  result += NewFile(ch.file, contents)
              }
            }
          case Left(e) =>
        }
    }
    result.toList
  }

  def writeChanges(changes: Iterable[FileEdit]): Either[Exception, Iterable[File]] = {
    val editsByFile = changes.collect { case ed: TextEdit => ed }.groupBy(_.file)
    val newFiles = changes.collect { case ed: NewFile => ed }
    try {
      val rewriteList = newFiles.map { ed => (ed.file, ed.text) } ++
        editsByFile.map {
          case (file, changes) =>
            readFile(file) match {
              case Right(contents) =>
                val newContents = FileEdit.applyEdits(changes.toList, contents)
                (file, newContents)
              case Left(e) => throw e
            }
        }

      val deleteFiles = changes.collect { case ed: DeleteFile => ed }
      for (ed <- deleteFiles) {
        ed.file.delete()
      }

      rewriteFiles(rewriteList) match {
        case Right(Right(_)) => Right(changes.groupBy(_.file).keys)
        case Right(Left(e)) => Left(new IllegalStateException(
          "Possibly incomplete write of change-set caused by: " + e))
        case Left(e) => Left(e)
      }
    } catch {
      case e: Exception => Left(e)
    }
  }

  /**
   * For each (f,s) pair, replace the contents of f with s. If any errors occurs
   * before any disk writes, return Left(exception). If  an error occurs DURING
   * disk writes, return Right(Left(exception)). Otherwise, return Right(Right(()))
   */
  def rewriteFiles(changes: Iterable[(File, String)]): Either[Exception, Either[Exception, Unit]] = {
    try {

      // Try to fail fast, before writing anything to disk.
      changes.foreach {
        case (f: File, s: String) => if (f.isDirectory || !f.canWrite) {
          throw new IllegalArgumentException(f + " is not a writable file.")
        }
        case _ =>
          throw new IllegalArgumentException("Invalid (File,String) pair.")
      }

      // Apply the changes. An error here may result in a corrupt disk state :(
      changes.foreach {
        case (file, newContents) =>
          replaceFileContents(file, newContents) match {
            case Right(_) =>
            case Left(e) => Right(Left(e))
          }
      }

      Right(Right(()))

    } catch {
      case e: Exception => Left(e)
    }
  }
}
