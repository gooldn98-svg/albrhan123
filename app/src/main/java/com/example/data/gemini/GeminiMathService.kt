package com.example.data.gemini

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.Question
import com.example.data.model.QuestionBank
import com.example.data.model.SubjectBranch
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig? = null,
    @Json(name = "systemInstruction") val systemInstruction: GeminiContent? = null
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "role") val role: String? = null,
    @Json(name = "parts") val parts: List<GeminiPart>
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "temperature") val temperature: Float = 0.2f,
    @Json(name = "topP") val topP: Float = 0.95f,
    @Json(name = "topK") val topK: Int = 40
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    @Json(name = "candidates") val candidates: List<GeminiCandidate>? = null
)

@JsonClass(generateAdapter = true)
data class GeminiCandidate(
    @Json(name = "content") val content: GeminiContent? = null
)

data class MathConceptSearchResult(
    val query: String,
    val conceptTitle: String,
    val branch: SubjectBranch,
    val summary: String,
    val rawAiResponse: String,
    val keyFormulas: List<String> = emptyList(),
    val stepByStepSolution: List<String> = emptyList(),
    val teacherTips: String? = null,
    val relatedQuestions: List<Question> = emptyList(),
    val isAiGenerated: Boolean = true
)

object GeminiMathService {

    private const val TAG = "GeminiMathService"
    // Using recommended modern preview model for STEM / Math reasoning
    private const val MODEL_NAME = "gemini-3.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)
    private val responseAdapter = moshi.adapter(GeminiResponse::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private const val SYSTEM_INSTRUCTION_TEXT = """
أنت "مساعد البرهان الذكي" - المعلم الافتراضي وخبير الرياضيات للصف الثالث الثانوي العلمي وفق المنهج اليمني وسلسلة الأستاذ القدير أنيس المقطري.
وظيفتك:
1. شرح المفاهيم الرياضية والقوانين والنظريات بدقة وبرهان منطقي مبسط.
2. حل المسائل الرياضية الوزارية خطوة بخطوة مع ذكر القانون المعتمد والتعويض والنتيجة النهائية.
3. التغطية الشاملة لفروع المنهج: الجبر (الأعداد المركبة، المصفوفات والمحددات، مبدأ العد وذات الحدين)، الهندسة التحليلية والفراغية (القطوع المخروطية، المتجهات في الفضاء)، التفاضل (النهايات، الاشتقاق، مبرهنات رول والقيمة المتوسطة، تطبيقات التفاضل)، التكامل (التكامل غير المحدود والمحدود، المساحات والحجوم)، الاحتمالات (الاحتمال الشرطي والمتغيرات العشوائية).
4. استخدم لغة عربية فصحى واضحة، ورتب الإجابة في نقاط واضحة:
   - 📌 المفهوم الأساسي أو نص النظرية
   - 📐 القانون الرياضي المستخدم
   - 🔢 خطوات الحل والبرهان التفصيلي
   - 💡 إضاءة وتنبيه وزاري (وفق إرشادات أ/ أنيس المقطري للاختبارات المؤتمتة)
"""

    suspend fun searchAndSolve(query: String): MathConceptSearchResult = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        val detectedBranch = detectBranch(trimmedQuery)
        val relatedQuestions = findRelatedQuestions(trimmedQuery, detectedBranch)

        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        val isKeyValid = apiKey.isNotBlank() && apiKey != "MY_GEMINI_API_KEY"

        if (!isKeyValid) {
            Log.w(TAG, "Gemini API key is placeholder or empty. Using curated curriculum knowledge engine.")
            return@withContext getCuratedFallbackResponse(trimmedQuery, detectedBranch, relatedQuestions)
        }

        try {
            val userPrompt = """
السؤال أو المفهوم الرياضي المطلوب من الطالب:
"$trimmedQuery"

يرجى تقديم شرح رياضي دقيق وشامل، أو حل المسألة خطوة بخطوة مع إبراز القوانين الرياضية والتنبيهات الوزارية المؤتمتة وفق المنهج اليمني.
"""
            val requestBody = GeminiRequest(
                contents = listOf(
                    GeminiContent(
                        role = "user",
                        parts = listOf(GeminiPart(text = userPrompt))
                    )
                ),
                systemInstruction = GeminiContent(
                    parts = listOf(GeminiPart(text = SYSTEM_INSTRUCTION_TEXT))
                ),
                generationConfig = GeminiGenerationConfig(
                    temperature = 0.25f,
                    topP = 0.95f,
                    topK = 40
                )
            )

            val jsonString = requestAdapter.toJson(requestBody)
            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(jsonString.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful && !responseBody.isNullOrBlank()) {
                val geminiResponse = responseAdapter.fromJson(responseBody)
                val responseText = geminiResponse?.candidates?.firstOrNull()
                    ?.content?.parts?.firstOrNull()?.text

                if (!responseText.isNullOrBlank()) {
                    return@withContext parseGeminiTextToResult(
                        query = trimmedQuery,
                        responseText = responseText,
                        branch = detectedBranch,
                        relatedQuestions = relatedQuestions
                    )
                }
            } else {
                Log.e(TAG, "Gemini API failed with code: ${response.code}, message: ${response.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini call exception: ${e.message}", e)
        }

        // Graceful fallback to rich curated repository if network drops or request fails
        getCuratedFallbackResponse(trimmedQuery, detectedBranch, relatedQuestions)
    }

    private fun parseGeminiTextToResult(
        query: String,
        responseText: String,
        branch: SubjectBranch,
        relatedQuestions: List<Question>
    ): MathConceptSearchResult {
        val lines = responseText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val formulas = mutableListOf<String>()
        val steps = mutableListOf<String>()
        var tips: String? = null

        for (line in lines) {
            if (line.contains("=") || line.contains("∫") || line.contains("lim") || line.contains("نها") || line.contains("د'(") || line.contains("جتا") || line.contains("جا")) {
                if (line.length < 90 && !line.startsWith("#")) {
                    formulas.add(line.removePrefix("- ").removePrefix("* ").trim())
                }
            }
            if (line.startsWith("1.") || line.startsWith("2.") || line.startsWith("3.") || line.startsWith("4.") || line.startsWith("- ") || line.startsWith("• ")) {
                steps.add(line.removePrefix("- ").removePrefix("• ").trim())
            }
            if (line.contains("إضاءة") || line.contains("تنبيه") || line.contains("ملاحظة") || line.contains("المقطري")) {
                tips = line
            }
        }

        return MathConceptSearchResult(
            query = query,
            conceptTitle = "مفهوم وشرح: $query",
            branch = branch,
            summary = lines.firstOrNull { it.length > 20 && !it.startsWith("#") } ?: "شرح تحليلي مدعوم بنماذج البرهان وGemini AI",
            rawAiResponse = responseText,
            keyFormulas = formulas.take(4),
            stepByStepSolution = if (steps.isNotEmpty()) steps.take(6) else lines.take(5),
            teacherTips = tips ?: "تأكد دائماً من كتابة الصورة القياسية ومراعاة إشارات الأرباع عند الإجابة على الأسئلة الوزارية المؤتمتة. (أ/ أنيس المقطري)",
            relatedQuestions = relatedQuestions,
            isAiGenerated = true
        )
    }

    private fun detectBranch(query: String): SubjectBranch {
        val q = query.lowercase()
        return when {
            q.contains("مركب") || q.contains("ديموافر") || q.contains("سعة") || q.contains("محدد") || q.contains("مصفوف") || q.contains("ذات الحدين") || q.contains("توافيق") || q.contains("تباديل") || q.contains("كرامر") -> SubjectBranch.ALGEBRA
            q.contains("قطع") || q.contains("ناقص") || q.contains("زائد") || q.contains("مكافئ") || q.contains("متجه") || q.contains("فضاء") || q.contains("بؤرة") || q.contains("دليل") || q.contains("اختلاف مركزي") -> SubjectBranch.GEOMETRY
            q.contains("نهاية") || q.contains("نهايات") || q.contains("اشتقاق") || q.contains("مشتقة") || q.contains("تفاضل") || q.contains("رول") || q.contains("قيمة متوسطة") || q.contains("ميل") || q.contains("لوبيتال") -> SubjectBranch.CALCULUS
            q.contains("تكامل") || q.contains("مساحة") || q.contains("حجم") || q.contains("دالة أصلية") || q.contains("تعويض") || q.contains("تجزئة") -> SubjectBranch.INTEGRATION
            q.contains("احتمال") || q.contains("شرطي") || q.contains("متغير عشوائي") || q.contains("استقلال") || q.contains("توقع") || q.contains("تباين") -> SubjectBranch.PROBABILITY
            else -> SubjectBranch.ALGEBRA
        }
    }

    private fun findRelatedQuestions(query: String, branch: SubjectBranch): List<Question> {
        val words = query.split(" ", "،", ",", "؟", "?").filter { it.length > 2 }
        val allQ = QuestionBank.allQuestions

        val matched = allQ.filter { q ->
            words.any { word ->
                q.questionText.contains(word, ignoreCase = true) ||
                q.unitName.contains(word, ignoreCase = true) ||
                q.lessonName.contains(word, ignoreCase = true) ||
                (q.mathEquation?.contains(word, ignoreCase = true) == true)
            }
        }

        return if (matched.isNotEmpty()) {
            matched.take(3)
        } else {
            allQ.filter { it.branch == branch }.take(2)
        }
    }

    private fun getCuratedFallbackResponse(
        query: String,
        branch: SubjectBranch,
        relatedQuestions: List<Question>
    ): MathConceptSearchResult {
        val q = query.lowercase()

        when {
            q.contains("ديموافر") || (q.contains("مركب") && q.contains("أس")) -> {
                return MathConceptSearchResult(
                    query = query,
                    conceptTitle = "مبرهنة ديموافر والجذور النونية للأعداد المركبة",
                    branch = SubjectBranch.ALGEBRA,
                    summary = "تنص مبرهنة ديموافر على أنه لأي عدد حقيقي θ وأي عدد صحيح ن فإن: [ر(جتا θ + ت جا θ)]ⁿ = رⁿ (جتا(ن θ) + ت جا(ن θ)).",
                    rawAiResponse = """
📌 **مبرهنة ديموافر (De Moivre's Theorem):**
تستخدم لحساب قوى وجذور الأعداد المركبة في الصورة القطبية المثلثية.

📐 **الصيغة الرياضية العامة:**
[ر (جتا θ + ت جا θ)]ⁿ = رⁿ [ جتا (ن θ) + ت جا (ن θ) ]

🔢 **خطوات الحل والتطبيق:**
1. تحويل العدد المركب ع = س + ت ص إلى الصورة المثلثية بإيجاد المقياس ر = √(س² + ص²) والسعة θ = ظا⁻¹(ص/س) مع مراعاة إشارة الربع.
2. تطبيق الأس ن على المقياس ر (رⁿ) وضربه في الزاوية θ (ن θ).
3. تبسيط الدوال المثلثية جتا(ن θ) و جا(ن θ) وحساب القيمة النهائية.

💡 **إضاءة وزاري مؤتمت (أ/ أنيس المقطري):**
- إذا كان الأس سالباً: (جتا θ + ت جا θ)⁻ⁿ = جتا(ن θ) - ت جا(ن θ).
- لا تنطبق المبرهنة مباشرة إذا كانت الإشارة سالبة في المنتصف أو تبدلت أماكن الجا والجتا حتى يتم تحويلها للصورة القياسية.
""".trimIndent(),
                    keyFormulas = listOf(
                        "(جتا θ + ت جا θ)ⁿ = جتا(ن θ) + ت جا(ن θ)",
                        "ع = ر (جتا θ + ت جا θ) حيث ر = √(س² + ص²)",
                        "الجذور النونية: ع^(1/ن) = ر^(1/ن) [جتا((θ + 2ك π)/ن) + ت جا((θ + 2ك π)/ن)]"
                    ),
                    stepByStepSolution = listOf(
                        "تحويل العدد للصورة القطبية القياسية ع = ر (جتا θ + ت جا θ).",
                        "توزيع الأس الصحيح ن على المقياس ر وضربه في الزاوية θ.",
                        "حساب قيم جتا(ن θ) و جا(ن θ) باستخدام الزوايا الخاصة والمكافئة.",
                        "كتابة الناتج النهائي بالصورة الجبرية أو المثلثية المطلوبة في السؤال الوزاري."
                    ),
                    teacherTips = "انتبه في الاختبارات المؤتمتة: تأكد دائماً أن الجزء الحقيقي هو (جتا) والتخيلي هو (جا) والإشارة بينهما موجبة قبل تطبيق المبرهنة! (أ/ أنيس المقطري)",
                    relatedQuestions = relatedQuestions,
                    isAiGenerated = false
                )
            }

            q.contains("رول") || q.contains("قيمة متوسطة") -> {
                return MathConceptSearchResult(
                    query = query,
                    conceptTitle = "مبرهنة رول ومبرهنة القيمة المتوسطة في التفاضل",
                    branch = SubjectBranch.CALCULUS,
                    summary = "مبرهنة رول تبحث عن نقطة حرجة جـ ينعدم عندها المماس (د'(جـ) = 0) بشرط اتصال واشتقاق الدالة وتساوي طرفي الفترة د(أ) = د(ب).",
                    rawAiResponse = """
📌 **شروط مبرهنة رول الثلاثة:**
1. د متصلة على الفترة المغلقة [أ ، ب].
2. د قابلة للاشتقاق على الفترة المفتوحة (أ ، ب).
3. د(أ) = د(ب).

📐 **النتيجة المنطقية:**
يوجد على الأقل جـ ∈ (أ ، ب) بحيث: د'(جـ) = 0 (المماس يوازي محور السينات).

📌 **مبرهنة القيمة المتوسطة:**
إذا تحققت شروط الاتصال والاشتقاق، فإنه يوجد جـ ∈ (أ ، ب) بحيث:
د'(جـ) = [ د(ب) - د(أ) ] / [ ب - أ ]  (ميل المماس = ميل القاطع).

💡 **إضاءة وزاري مؤتمت (أ/ أنيس المقطري):**
- يجب أن تنتمي النقطة جـ إلى الفترة *المفتوحة* (أ ، ب) حصراً، فإذا كانت جـ تساوي أحد طرفي الفترة ترفض مباشرة.
""".trimIndent(),
                    keyFormulas = listOf(
                        "مبرهنة رول: د'(جـ) = 0 حيث جـ ∈ (أ ، ب)",
                        "القيمة المتوسطة: د'(جـ) = (د(ب) - د(أ)) / (ب - أ)",
                        "ميل القاطع = [د(ب) - د(أ)] / [ب - أ]"
                    ),
                    stepByStepSolution = listOf(
                        "فحص اتصال الدالة وقابليتها للاشتقاق على الفترة المحددة.",
                        "التحقق من شرط تساوي قيمتي الدالة عند طرفي الفترة: د(أ) = د(ب).",
                        "إيجاد مشتقة الدالة د'(س) والتعويض عن س بالرمز جـ.",
                        "مساواة د'(جـ) بالصفر وحل المعادلة الناتجة لإيجاد جـ مع التأكد من وقوعها داخل (أ ، ب)."
                    ),
                    teacherTips = "في أسئلة اختر الإجابة الصحيحة: أي قيمة لـ (جـ) تقع خارج الفترة المفتوحة أو عند الأطراف تستبعد فوراً. (أ/ أنيس المقطري)",
                    relatedQuestions = relatedQuestions,
                    isAiGenerated = false
                )
            }

            q.contains("قطع") || q.contains("ناقص") || q.contains("زائد") || q.contains("مكافئ") || q.contains("اختلاف") -> {
                return MathConceptSearchResult(
                    query = query,
                    conceptTitle = "القطوع المخروطية والاختلاف المركزي",
                    branch = SubjectBranch.GEOMETRY,
                    summary = "القطع المخروطي هو المحل الهندسي لنقطة تتحرك بحيث تكون النسبة بين بعدها عن نقطة ثابتة (البؤرة) وبعدها عن مستقيم ثابت (الدليل) تساوي مقداراً ثابتاً يسمى الاختلاف المركزي (ي).",
                    rawAiResponse = """
📌 **تصنيف القطوع المخروطية حسب الاختلاف المركزي (ي = جـ / أ):**
1. القطع المكافئ: ي = 1.
2. القطع الناقص: 0 < ي < 1 (ومنها الدائرة كحالة خاصة ي = 0).
3. القطع الزائد: ي > 1.

📐 **العلاقات الأساسية بين الثوابت (أ ، ب ، جـ):**
- في القطع الناقص: أ² = ب² + جـ²  (حيث أ هو الأكبر دائماً).
- في القطع الزائد: جـ² = أ² + ب²  (حيث جـ هو الأكبر دائماً).
- معادلة القطع الناقص القياسية: (س²/أ²) + (ص²/ب²) = 1.
- معادلة القطع الزائد القياسية: (س²/أ²) - (ص²/ب²) = 1.

💡 **إضاءة وزاري مؤتمت (أ/ أنيس المقطري):**
- معادلة المحاذيين للقطع الزائد (س²/أ² - ص²/ب² = 1) هي: ص = ± (ب / أ) س.
""".trimIndent(),
                    keyFormulas = listOf(
                        "الاختلاف المركزي: ي = جـ / أ",
                        "القطع الناقص: أ² = ب² + جـ²  (0 < ي < 1)",
                        "القطع الزائد: جـ² = أ² + ب²  (ي > 1)",
                        "القطع المكافئ: ص² = 4 أ س  (ي = 1)"
                    ),
                    stepByStepSolution = listOf(
                        "كتابة معادلة القطع المعطاة بالصورة القياسية بجعل الطرف الأيسر مساوياً للواحد الصحيح.",
                        "تحديد موقع المحور البؤري (سيني أو صادي) عبر مقارنة المقامات.",
                        "حساب قيم أ و ب ثم استخراج البعد البؤري جـ من العلاقة الأساسية.",
                        "إيجاد إحداثيات الرأسين والبؤرتين والاختلاف المركزي ي = جـ/أ."
                    ),
                    teacherTips = "في القطع الناقص الرقم الأكبر تحت س² أو ص² هو أ²، أما في القطع الزائد فإن الكسر الموجب هو صاحب أ² دائماً بغض النظر عن المقدار! (أ/ أنيس المقطري)",
                    relatedQuestions = relatedQuestions,
                    isAiGenerated = false
                )
            }

            q.contains("تكامل") || q.contains("مساحة") || q.contains("تعويض") -> {
                return MathConceptSearchResult(
                    query = query,
                    conceptTitle = "طرق التكامل وحساب المساحات الهندسية المستوية",
                    branch = SubjectBranch.INTEGRATION,
                    summary = "التكامل هو العملية العكسية للتفاضل. يستخدم التكامل المحدود لحساب المساحة المحصورة بين المنحنيات ومحاور الإحداثيات.",
                    rawAiResponse = """
📌 **قوانين التكامل الأساسية:**
1. ∫ سⁿ دس = (سⁿ⁺¹ / (ن + 1)) + ث  (حيث ن ≠ -1).
2. ∫ د'(س) [د(س)]ⁿ دس = [د(س)]ⁿ⁺¹ / (ن + 1) + ث.
3. ∫ د'(س) هـ^(د(س)) دس = هـ^(د(س)) + ث.
4. ∫ [ د'(س) / د(س) ] دس = لو_هـ |د(س)| + ث.

📐 **حساب مساحة المنطقة المستوية:**
المساحة م المحصورة بين منحنى د(س) ومحور السينات في الفترة [أ ، ب] هي:
م = ∫_أ^ب | د(س) | دس

💡 **إضاءة وزاري مؤتمت (أ/ أنيس المقطري):**
- إذا قطع المنحنى محور السينات داخل الفترة [أ ، ب] يجب إيجاد نقاط التقاطع بتصفير د(س) وتجزئة التكامل لضمان بقاء المساحة موجبة.
""".trimIndent(),
                    keyFormulas = listOf(
                        "م = ∫_أ^ب | د(س) | دس",
                        "∫ د'(س) / د(س) دس = لو_هـ |د(س)| + ث",
                        "التكامل بالتجزئة: ∫ ص دع = ص ع - ∫ ع دص"
                    ),
                    stepByStepSolution = listOf(
                        "إيجاد نقاط تقاطع منحنى الدالة مع محور السينات بحل المعادلة د(س) = 0.",
                        "تحديد الفترات الجزئية ودراسة إشارة الدالة في كل فترة.",
                        "إجراء التكامل المحدود لكل فترة جزئية مع أخذ القيمة المطلقة للمساحة.",
                        "جمع المساحات الجزئية للحصول على المساحة الكلية بوحدات مربعة."
                    ),
                    teacherTips = "المساحة والحجم كميات موجبة دائماً، إذا ظهر لك ناتج سالب فاعلم أنك نسيت وضع القيمة المطلقة للتكامل! (أ/ أنيس المقطري)",
                    relatedQuestions = relatedQuestions,
                    isAiGenerated = false
                )
            }

            else -> {
                return MathConceptSearchResult(
                    query = query,
                    conceptTitle = "مفهوم رياضي وبنك القوانين: $query",
                    branch = branch,
                    summary = "استعراض القواعد الرياضية والبرهان المنطقي المعتمد لمفهوم ($query) وفق المنهج اليمني وسلسلة التميز للأستاذ أنيس المقطري.",
                    rawAiResponse = """
📌 **تحليل السؤال والمفهوم ($query):**
يعد هذا الموضوع من الركائز الأساسية في منهج الرياضيات للصف الثالث الثانوي العلمي.

📐 **القوانين والإجراءات المعتمدة:**
1. تحديد المعطيات الرياضية والصيغة القياسية للمسألة.
2. تطبيق القواعد الجبرية والتفاضلية المناسبة.
3. التحقق من شروط النظريات والتعويض بدقة في كل خطوة.

💡 **إضاءة وزاري مؤتمت (أ/ أنيس المقطري):**
- احرص على قراءة المسألة بعناية وتدوين القوانين الهندسية والجبرية المعتمدة قبل اختيار الإجابة المؤتمتة.
""".trimIndent(),
                    keyFormulas = listOf(
                        "القاعدة العامة: حل المعادلة والبرهان خطوة بخطوة",
                        "التحقق من صحة الفرضيات والشروط المنطقية"
                    ),
                    stepByStepSolution = listOf(
                        "تحديد المعطيات والشروط المقترنة بالسؤال: $query",
                        "تطبيق القانون الرياضي المعتمد وفق فرع ${branch.titleAr}.",
                        "التبسيط الجبري الدقيق ومراعاة إشارات الأعداد والزوايا.",
                        "الوصول إلى النتيجة النهائية والتحقق منها."
                    ),
                    teacherTips = "مساعد البرهان الذكي وGemini AI جاهز للإجابة وتفسير أي مسألة تفصيلية تطرحها! (أ/ أنيس المقطري)",
                    relatedQuestions = relatedQuestions,
                    isAiGenerated = false
                )
            }
        }
    }
}
