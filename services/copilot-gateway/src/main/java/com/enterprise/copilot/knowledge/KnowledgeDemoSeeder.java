package com.enterprise.copilot.knowledge;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds the offline demo's knowledge base.
 *
 * <p>Registered only under the {@code dev} profile, alongside the demo token endpoint: a
 * production gateway must never ship with fabricated policy documents that answers could cite.
 * Ingest is an upsert, so restarting against the persistent dev database refreshes rather than
 * duplicates.
 */
@Component
@Profile("dev")
public class KnowledgeDemoSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(KnowledgeDemoSeeder.class);

  /** Matches the tenant and application the demo token mints. */
  private static final String TENANT = "demo";

  private static final String APP = "crm";

  private final KnowledgeService knowledgeService;

  public KnowledgeDemoSeeder(KnowledgeService knowledgeService) {
    this.knowledgeService = knowledgeService;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!knowledgeService.isEnabled()) {
      return;
    }
    try {
      for (KnowledgeService.DocumentInput document : documents()) {
        knowledgeService.upsert(TENANT, document);
      }
      log.info("DEV PROFILE: seeded {} demo knowledge documents for tenant '{}'", documents().size(), TENANT);
    } catch (RuntimeException ex) {
      // Sample data is a convenience; never let it stop the gateway from starting.
      log.warn("Failed to seed demo knowledge base", ex);
    }
  }

  private static List<KnowledgeService.DocumentInput> documents() {
    return List.of(
        new KnowledgeService.DocumentInput(
            "refund-policy",
            APP,
            "退货与退款政策",
            "https://intranet.example.com/kb/refund-policy",
            List.of("售后", "政策"),
            """
            适用范围：本政策适用于所有通过 CRM 系统下单的企业客户。

            退货时限：客户可在签收后 30 个自然日内申请退货。超过 30 天的订单不再受理无理由退货，\
            但仍可按质量问题走质保流程。

            退款时效：退货入库并质检通过后，财务在 5 个工作日内原路退款。银行到账时间以发卡行为准，\
            通常再需 1 至 3 个工作日。

            例外情况：定制加工类商品、已开具增值税专用发票且已认证抵扣的订单，不支持无理由退货，\
            需由销售经理提交例外申请。
            """),
        new KnowledgeService.DocumentInput(
            "order-approval-sla",
            APP,
            "订单审批时效与升级规则",
            "https://intranet.example.com/kb/order-approval-sla",
            List.of("流程", "时效"),
            """
            标准时效：金额低于 10 万元的订单，由销售经理在 4 小时内完成审批。

            大额订单：金额达到或超过 10 万元的订单需经区域总监复核，整体时效为 1 个工作日。

            超时升级：审批超过时效未处理的订单，系统自动升级至上一级主管，并在企业微信推送提醒。

            加急通道：标记为「加急」的订单优先进入审批队列，承诺 2 小时内给出结论；\
            「特急」需电话确认后由值班主管即时处理。
            """),
        new KnowledgeService.DocumentInput(
            "invoice-guide",
            APP,
            "发票开具说明",
            "https://intranet.example.com/kb/invoice-guide",
            List.of("财务", "发票"),
            """
            开票时点：订单状态变为「已发货」后即可申请开票，未发货订单无法开票。

            发票类型：默认开具增值税普通发票；需要专用发票的客户，须先在客户档案中登记\
            纳税人识别号、开户行与账号。

            开票时效：财务在收到申请后 3 个工作日内完成开具，电子发票通过邮件发送，\
            纸质发票随货或次日顺丰寄出。

            发票作废与重开：当月发票可直接作废重开；跨月需先由客户寄回原票并申请红字发票。
            """));
  }
}
